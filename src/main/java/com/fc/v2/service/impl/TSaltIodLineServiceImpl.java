package com.fc.v2.service.impl;

import java.math.BigDecimal;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.mapper.auto.TSaltIodLineMapper;
import com.fc.v2.model.auto.TSaltIodLine;
import com.fc.v2.model.custom.IodLineValidity;
import com.fc.v2.model.custom.IodOrphan;
import com.fc.v2.model.custom.IodReportRef;
import com.fc.v2.model.custom.IodVerdict;
import com.fc.v2.service.ITSaltIodLineService;

/**
 * 碘含量分档判定线 Service 业务层（rule-eval 形状：只读线册）。
 *
 * 全册只有一个加载口 {@link #loadLedger()}（del_flag=0），在场过滤只在 Java 侧按问话
 * 时刻做一遍——屏上名单与"把线册逐条走一遍"凑出来的名单出处同一处。时刻比较一律走
 * java.util.Date 毫秒数，不把时刻塞进 SQL：JDBC serverTimezone 与本机时区不对称时，
 * Date 作绑定参数会整体偏移，切换当日容易错档（旧实现踩过）。
 *
 * @author fuce
 * @date 2026-10-02
 */
@Service
public class TSaltIodLineServiceImpl implements ITSaltIodLineService {

    /** 结论三档 */
    public static final String GRADE_LOW = "偏低";
    public static final String GRADE_OK = "合规";
    public static final String GRADE_OVER = "过档";
    /** 三条都够不上在场时的结论 */
    public static final String GRADE_NONE = "无可用线";

    /** 线的情形：0 在场，1 已停用 */
    private static final int STATUS_ACTIVE = 0;

    @javax.annotation.Resource
    private TSaltIodLineMapper saltIodLineMapper;

    /** 中文排法；线代号并列决胜时取排法末位 */
    private static final Collator COLLATOR = Collator.getInstance(Locale.CHINA);

    /**
     * 说话顺位：让位顺位 priority 大者先说话（null 视作 0）；顺位相同再比线代号，
     * 照排法落在末位（次序靠后）的胜出。
     */
    private static final Comparator<TSaltIodLine> SPEAK_ORDER = new Comparator<TSaltIodLine>() {
        @Override
        public int compare(TSaltIodLine a, TSaltIodLine b) {
            int pa = a.getPriority() == null ? 0 : a.getPriority();
            int pb = b.getPriority() == null ? 0 : b.getPriority();
            if (pa != pb) {
                return pb - pa;
            }
            String ca = a.getRuleCode() == null ? "" : a.getRuleCode();
            String cb = b.getRuleCode() == null ? "" : b.getRuleCode();
            return COLLATOR.compare(cb, ca);
        }
    };

    @Override
    public TSaltIodLine selectTSaltIodLineById(Long id) {
        if (id == null) {
            return null;
        }
        return this.saltIodLineMapper.selectById(id);
    }

    @Override
    public List<TSaltIodLine> listLedger() {
        return loadLedger();
    }

    @Override
    public IodLineValidity validate(TSaltIodLine line) {
        IodLineValidity v = new IodLineValidity();
        if (line == null) {
            v.setValid(false);
            v.getErrors().add("线册条目是空的，这条线立不起来");
            return v;
        }

        // 三个分界数缺一个，这条线立不起来
        if (line.getTh1Max() == null) {
            addField(v, "th1Max", "起算数值（th1Max）栏空着，三个分界数缺一个，这条线立不起来");
        }
        if (line.getTh2Max() == null) {
            addField(v, "th2Max", "够线数值（th2Max）栏空着，三个分界数缺一个，这条线立不起来");
        }
        if (line.getTh3Max() == null) {
            addField(v, "th3Max", "封顶数值（th3Max）栏空着，三个分界数缺一个，这条线立不起来");
        }
        if (line.getEffStart() == null) {
            addField(v, "effStart", "立线之日（effStart）栏空着，这条线立不起来");
        }

        // 后一个分界数不比前面的大（须严格越过），这一线就立不住；空栏已先记，不重复比
        if (line.getTh1Max() != null && line.getTh2Max() != null
                && line.getTh2Max().compareTo(line.getTh1Max()) <= 0) {
            addField(v, "th2Max",
                    "够线数值（th2Max）没有越过起算数值（th1Max），后面那栏须严格大于前头一栏，这条线立不住");
        }
        if (line.getTh2Max() != null && line.getTh3Max() != null
                && line.getTh3Max().compareTo(line.getTh2Max()) <= 0) {
            addField(v, "th3Max",
                    "封顶数值（th3Max）没有越过够线数值（th2Max），后面那栏须严格大于前头一栏，这条线立不住");
        }

        // 让位之日不晚于立线之日，窗口撑不起来
        if (line.getEffStart() != null && line.getEffEnd() != null
                && !line.getEffEnd().after(line.getEffStart())) {
            addField(v, "effEnd", "让位之日（effEnd）没有晚于立线之日（effStart），这一线撑不起来");
        }

        v.setValid(v.getErrors().isEmpty());
        return v;
    }

    @Override
    public List<TSaltIodLine> listAvailable(Date at) {
        if (at == null) {
            return new ArrayList<TSaltIodLine>();
        }
        List<TSaltIodLine> present = new ArrayList<TSaltIodLine>();
        for (TSaltIodLine r : loadLedger()) {
            if (isPresent(r, at)) {
                present.add(r);
            }
        }
        Collections.sort(present, SPEAK_ORDER);
        return present;
    }

    @Override
    public TSaltIodLine resolveLine(Date at) {
        List<TSaltIodLine> present = listAvailable(at);
        return present.isEmpty() ? null : present.get(0);
    }

    @Override
    public TSaltIodLine resolveLineByCode(String ruleCode, Date at) {
        if (ruleCode == null || ruleCode.trim().isEmpty() || at == null) {
            return null;
        }
        List<TSaltIodLine> hit = new ArrayList<TSaltIodLine>();
        for (TSaltIodLine r : listAvailable(at)) {
            if (ruleCode.equals(r.getRuleCode())) {
                hit.add(r);
            }
        }
        if (hit.isEmpty()) {
            // 绝不退回"取最新一条"：新线顶替旧档的旧错就堵在这里
            return null;
        }
        // listAvailable 已按说话顺位排过；同一代号理论上至多一条在场，取决胜第一条兜底
        return hit.get(0);
    }

    @Override
    public IodVerdict judge(BigDecimal iodine, Date at) {
        if (iodine == null || at == null) {
            return noLine(iodine, at);
        }
        TSaltIodLine line = resolveLine(at);
        if (line == null) {
            return noLine(iodine, at);
        }
        return verdictOf(line, iodine, at);
    }

    @Override
    public IodVerdict judgeByCode(String ruleCode, BigDecimal iodine, Date at) {
        if (iodine == null || at == null) {
            return noLine(iodine, at);
        }
        TSaltIodLine line = resolveLineByCode(ruleCode, at);
        if (line == null) {
            // 挂着的代号那一刻请不出在场线：按无可用线回话，不许换旁边在场的线顶替
            return noLine(iodine, at);
        }
        return verdictOf(line, iodine, at);
    }

    @Override
    public IodVerdict judgeBatch(BigDecimal iodine, Date sampleAt, Date sealAt) {
        if (iodine == null || sampleAt == null || sealAt == null) {
            return noLine(iodine, sampleAt);
        }
        // 取样、封样同用一条线：两个入口各问一遍，回话必须一模一样
        TSaltIodLine sampleLine = resolveLine(sampleAt);
        TSaltIodLine sealLine = resolveLine(sealAt);
        boolean same = sampleLine != null && sealLine != null
                && sampleLine.getId() != null && sampleLine.getId().equals(sealLine.getId());
        if (!same) {
            IodVerdict v = new IodVerdict();
            v.setIodine(iodine);
            v.setAt(sampleAt);
            v.setConflict(true);
            v.setGrade(null);
            if (sampleLine != null) {
                v.setRuleCode(sampleLine.getRuleCode());
                v.setEffStart(sampleLine.getEffStart());
                v.setRuleId(sampleLine.getId());
                v.setRuleName(sampleLine.getRuleName());
            }
            if (sealLine != null) {
                v.setOtherRuleCode(sealLine.getRuleCode());
                v.setOtherEffStart(sealLine.getEffStart());
            }
            if (sampleLine == null && sealLine == null) {
                // 两刻都请不出线，按无可用线落字，不留跨线假象
                return noLine(iodine, sampleAt);
            }
            return v;
        }
        return verdictOf(sampleLine, iodine, sampleAt);
    }

    @Override
    public boolean usable(Long id, Date at) {
        if (id == null || at == null) {
            return false;
        }
        for (TSaltIodLine r : loadLedger()) {
            if (id.equals(r.getId()) && isPresent(r, at)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public int countAvailable(Date at) {
        return listAvailable(at).size();
    }

    @Override
    public List<IodOrphan> findOrphans(List<IodReportRef> refs) {
        List<IodOrphan> orphans = new ArrayList<IodOrphan>();
        if (refs == null) {
            return orphans;
        }
        List<TSaltIodLine> ledger = loadLedger();
        for (IodReportRef ref : refs) {
            if (ref == null) {
                continue;
            }
            String code = ref.getRuleCode();
            if (code == null || code.trim().isEmpty()) {
                orphans.add(new IodOrphan(ref.getBizNo(), code, ref.getAt(), "结论栏挂着的线代号空着，线册无从请线"));
                continue;
            }
            List<TSaltIodLine> sameCode = new ArrayList<TSaltIodLine>();
            for (TSaltIodLine r : ledger) {
                if (code.equals(r.getRuleCode())) {
                    sameCode.add(r);
                }
            }
            if (sameCode.isEmpty()) {
                orphans.add(new IodOrphan(ref.getBizNo(), code, ref.getAt(),
                        "线册里查无代号 " + code + " 的线，那一档请不出在场的线"));
                continue;
            }
            Date at = ref.getAt();
            if (at == null) {
                orphans.add(new IodOrphan(ref.getBizNo(), code, at, "报告自己的问话时刻空着，无法回线册核对"));
                continue;
            }
            boolean present = false;
            for (TSaltIodLine r : sameCode) {
                if (isPresent(r, at)) {
                    present = true;
                    break;
                }
            }
            if (!present) {
                orphans.add(new IodOrphan(ref.getBizNo(), code, at, absentReason(sameCode, at)));
            }
        }
        return orphans;
    }

    // ------------------------------------------------------------------
    // 内部口径（只此一份）
    // ------------------------------------------------------------------

    /** 线册唯一加载口：未删除的全量；停用线也在册（翻检、列出得到），只是判档时被剔 */
    private List<TSaltIodLine> loadLedger() {
        return this.saltIodLineMapper.selectList(new QueryWrapper<TSaltIodLine>().eq("del_flag", 0));
    }

    /**
     * 一条线在 at 刻是否在场：三个分界数须齐全且严格递增（缺一个/后不越过前，这线立
     * 不住，不许多嘴）；情形须为"在场"（已停用不参与现行判定）；立线之日已经够着
     * （&gt;=，含该日）、让位之日还没到（&lt;，不含该日）；让位之日空着认作一直在场
     * （早年手抄进册的存量开口线）。
     */
    private boolean isPresent(TSaltIodLine r, Date at) {
        if (!thresholdsWellFormed(r)) {
            return false;
        }
        if (r.getStatus() == null || r.getStatus() != STATUS_ACTIVE) {
            return false;
        }
        if (r.getEffStart() == null || at.before(r.getEffStart())) {
            return false;
        }
        if (r.getEffEnd() != null && !at.before(r.getEffEnd())) {
            return false;
        }
        return true;
    }

    /** 三个分界数一个不缺、且后一栏严格越过前一栏，线才立得住 */
    private boolean thresholdsWellFormed(TSaltIodLine r) {
        return r.getTh1Max() != null && r.getTh2Max() != null && r.getTh3Max() != null
                && r.getTh2Max().compareTo(r.getTh1Max()) > 0
                && r.getTh3Max().compareTo(r.getTh2Max()) > 0;
    }

    /**
     * 请不出在场线时的缘由指名（同一代号的各版本都不在场，按最贴切的一条回话）。
     */
    private String absentReason(List<TSaltIodLine> sameCode, Date at) {
        boolean stopped = false;
        boolean notYet = false;
        boolean expired = false;
        for (TSaltIodLine r : sameCode) {
            if (r.getStatus() != null && r.getStatus() != STATUS_ACTIVE) {
                stopped = true;
            } else if (r.getEffStart() != null && at.before(r.getEffStart())) {
                notYet = true;
            } else if (r.getEffEnd() != null && !at.before(r.getEffEnd())) {
                expired = true;
            }
        }
        if (stopped && !notYet && !expired) {
            return "该线已停用，不许捞出来比数";
        }
        if (expired) {
            return "该刻已过让位之日，旧线那时已经让位";
        }
        if (notYet) {
            return "该刻还没到立线之日，新线那时尚未开口";
        }
        return "该刻请不出这条代号的在场线";
    }

    /**
     * 三档比数，一条走法，不留空档：
     * 够不着够线（x &lt; th2，含够不着起算 th1 那段）记偏低；
     * 够着够线又压不过封顶（th2 &lt;= x &lt;= th3）记合规；
     * 压过封顶（x &gt; th3）记过档。
     * 起算 th1 与够线 th2 之间[th1,th2)够不着够线，归入偏低；等号落在"够着/压不过"一侧。
     */
    private IodVerdict verdictOf(TSaltIodLine line, BigDecimal iodine, Date at) {
        IodVerdict v = new IodVerdict();
        v.setIodine(iodine);
        v.setAt(at);
        v.setRuleId(line.getId());
        v.setRuleCode(line.getRuleCode());
        v.setRuleName(line.getRuleName());
        v.setEffStart(line.getEffStart());
        if (iodine.compareTo(line.getTh2Max()) < 0) {
            v.setGrade(GRADE_LOW);
        } else if (iodine.compareTo(line.getTh3Max()) <= 0) {
            v.setGrade(GRADE_OK);
        } else {
            v.setGrade(GRADE_OVER);
        }
        return v;
    }

    /** 无可用线回话：结论落"无可用线"，线代号与立线之日一并留空，不抄旁边那条的数字 */
    private IodVerdict noLine(BigDecimal iodine, Date at) {
        IodVerdict v = new IodVerdict();
        v.setGrade(GRADE_NONE);
        v.setRuleCode(null);
        v.setEffStart(null);
        v.setIodine(iodine);
        v.setAt(at);
        return v;
    }

    private void addField(IodLineValidity v, String field, String message) {
        v.getFieldErrors().put(field, message);
        v.getErrors().add(message);
    }
}
