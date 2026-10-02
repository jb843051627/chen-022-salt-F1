package com.fc.v2.service.impl;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

import javax.annotation.Resource;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.common.support.ConvertUtil;
import com.fc.v2.mapper.auto.TSaltIodLineMapper;
import com.fc.v2.model.auto.TSaltIodLine;
import com.fc.v2.model.custom.IodLineVerdict;
import com.fc.v2.service.ITSaltIodLineService;

/**
 * 碘含量分档判定线 Service业务层处理（rule-eval 形状）。
 *
 * 同一份报告从两个入口各问一遍回话必须一模一样：resolveByCode / usable / countAvailable /
 * 在场名单 / 定档全部只认 {@link #presentAt} 这一条在场口径，名单只在 {@link #listAvailable}
 * 排一次序，定档取排好的头一条，不存在第二条选线走法。
 *
 * @author fuce
 * @date 2026-10-02
 */
@Service
public class TSaltIodLineServiceImpl implements ITSaltIodLineService {

    @Resource
    private TSaltIodLineMapper saltIodLineMapper;

    /** 说话次序：让位顺位数值大的先说话；顺位相同再比线代号，照字符串排法落在末位者胜出。 */
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
            int byCode = cb.compareTo(ca);
            if (byCode != 0) {
                return byCode;
            }
            // 同代号正常只允许首尾相接、不许重叠（保存时把住）；id 仅作末位兜底，保证排序确定
            Long ia = a.getId() == null ? Long.MIN_VALUE : a.getId();
            Long ib = b.getId() == null ? Long.MIN_VALUE : b.getId();
            return ib.compareTo(ia);
        }
    };

    @Override
    public TSaltIodLine selectTSaltIodLineById(Long id) {
        return saltIodLineMapper.selectById(id);
    }

    @Override
    public List<TSaltIodLine> listBook() {
        // 全册：已停用也列得出，只剔除删除
        return saltIodLineMapper.selectList(new QueryWrapper<TSaltIodLine>()
                .eq("del_flag", 0)
                .orderByAsc("eff_start")
                .orderByAsc("rule_code"));
    }

    @Override
    public List<TSaltIodLine> listAvailable(Date at) {
        if (at == null) {
            return new ArrayList<TSaltIodLine>();
        }
        List<TSaltIodLine> all = saltIodLineMapper.selectList(
                new QueryWrapper<TSaltIodLine>().eq("del_flag", 0));
        List<TSaltIodLine> present = new ArrayList<TSaltIodLine>();
        for (TSaltIodLine line : all) {
            if (presentAt(line, at)) {
                present.add(line);
            }
        }
        Collections.sort(present, SPEAK_ORDER);
        return present;
    }

    @Override
    public int countAvailable(Date at) {
        return listAvailable(at).size();
    }

    @Override
    public boolean usable(Long id, Date at) {
        if (id == null || at == null) {
            return false;
        }
        TSaltIodLine line = saltIodLineMapper.selectById(id);
        return line != null && presentAt(line, at);
    }

    @Override
    public TSaltIodLine resolveByCode(String ruleCode, Date at) {
        if (ruleCode == null || ruleCode.trim().isEmpty() || at == null) {
            return null;
        }
        for (TSaltIodLine line : listAvailable(at)) {
            if (ruleCode.equals(line.getRuleCode())) {
                return line;
            }
        }
        return null;
    }

    @Override
    public IodLineVerdict judge(BigDecimal iodine, Date at) {
        if (iodine == null || at == null) {
            // 参数不全无法问话，与"三条都够不上"同走无可用线，两栏留空
            return IodLineVerdict.none(iodine, at);
        }
        List<TSaltIodLine> present = listAvailable(at);
        if (present.isEmpty()) {
            return IodLineVerdict.none(iodine, at);
        }
        // 在场名单已排过说话次序，头一条即唯一说话者，同一刻只会问出一个档位
        return IodLineVerdict.of(present.get(0), levelOf(present.get(0), iodine), iodine, at);
    }

    @Override
    public IodLineVerdict judgeByCode(String ruleCode, BigDecimal iodine, Date at) {
        TSaltIodLine line = resolveByCode(ruleCode, at);
        if (line == null) {
            // 结论上挂着代号、那一刻却请不出在场线：不许缀在结论后头
            return IodLineVerdict.none(iodine, at);
        }
        return IodLineVerdict.of(line, levelOf(line, iodine), iodine, at);
    }

    @Override
    public List<DanglingRef> findDangling(List<DanglingRef> entries) {
        List<DanglingRef> dangling = new ArrayList<DanglingRef>();
        if (entries == null) {
            return dangling;
        }
        for (DanglingRef ref : entries) {
            if (ref == null || ref.getAt() == null) {
                continue;
            }
            if (resolveByCode(ref.getRuleCode(), ref.getAt()) == null) {
                dangling.add(ref);
            }
        }
        return dangling;
    }

    @Override
    public int insertTSaltIodLine(TSaltIodLine record) {
        if (record == null) {
            return 0;
        }
        validateThresholds(record);
        rejectCodeOverlap(record, null);
        record.setDelFlag(0);
        if (record.getStatus() == null) {
            record.setStatus(0);
        }
        if (record.getPriority() == null) {
            record.setPriority(0);
        }
        record.setCreateTime(new Date());
        return saltIodLineMapper.insert(record);
    }

    @Override
    public int updateTSaltIodLine(TSaltIodLine record) {
        if (record == null || record.getId() == null) {
            return 0;
        }
        validateThresholds(record);
        rejectCodeOverlap(record, record.getId());
        record.setUpdateTime(new Date());
        return saltIodLineMapper.updateById(record);
    }

    @Override
    public int deactivate(Long id) {
        if (id == null) {
            return 0;
        }
        TSaltIodLine line = new TSaltIodLine();
        line.setId(id);
        line.setStatus(1);
        line.setUpdateTime(new Date());
        return saltIodLineMapper.updateById(line);
    }

    @Override
    public int deleteTSaltIodLineByIds(String ids) {
        if (ids == null || ids.trim().isEmpty()) {
            return 0;
        }
        Long[] idArr = ConvertUtil.toLongArray(ids);
        int rows = 0;
        for (Long id : idArr) {
            TSaltIodLine line = new TSaltIodLine();
            line.setId(id);
            line.setDelFlag(1);
            line.setUpdateTime(new Date());
            rows += saltIodLineMapper.updateById(line);
        }
        return rows;
    }

    /**
     * 在场口径（全册唯一一处）：
     * 立线之日已够着（含立线当日）、让位之日还没到（eff_end 为空即存量线，认作一直在场；
     * 非空则不含让位当日，交接那一日起归新线）；情形须为在场（status=0）。
     */
    private boolean presentAt(TSaltIodLine line, Date at) {
        if (line == null) {
            return false;
        }
        // usable() 按主键直取不走列表的 del_flag 过滤，这里统一兜住
        if (line.getDelFlag() != null && line.getDelFlag() == 1) {
            return false;
        }
        if (line.getStatus() != null && line.getStatus() == 1) {
            return false;
        }
        if (line.getEffStart() == null || at.before(line.getEffStart())) {
            return false;
        }
        if (line.getEffEnd() != null && !at.before(line.getEffEnd())) {
            return false;
        }
        return true;
    }

    /**
     * 三档落字（只有这一条走法）：
     * 够不着起算 th1 记偏低；够着够线 th2 又压不过封顶 th3 记合规；压过封顶记过档。
     * 阈值严格递增已在立线/改线时把住；起算与够线之间按"偏低"（还没够着够线）处理。
     * 边界：等于够线、等于封顶都归合规。
     */
    private String levelOf(TSaltIodLine line, BigDecimal iodine) {
        if (iodine.compareTo(line.getTh2Max()) < 0) {
            return LEVEL_LOW;
        }
        if (iodine.compareTo(line.getTh3Max()) > 0) {
            return LEVEL_OVER;
        }
        return LEVEL_OK;
    }

    /**
     * 同一线代号的在场窗口只许首尾相接、不许重叠：
     * 否则交接期内同一刻请出两条同代号的线，"同一份报告问两遍回话一样"就守不住。
     * 已停用的线不参与在场，不挡重叠；窗口为半开区间 [effStart, effEnd)，端点相接（旧线 effEnd = 新线 effStart）允许。
     */
    private void rejectCodeOverlap(TSaltIodLine record, Long excludeId) {
        if (record.getRuleCode() == null || record.getRuleCode().trim().isEmpty()) {
            return;
        }
        Date s1 = record.getEffStart();
        Date e1 = record.getEffEnd();
        List<TSaltIodLine> sameCode = saltIodLineMapper.selectList(new QueryWrapper<TSaltIodLine>()
                .eq("del_flag", 0)
                .eq("status", 0)
                .eq("rule_code", record.getRuleCode()));
        for (TSaltIodLine other : sameCode) {
            if (excludeId != null && excludeId.equals(other.getId())) {
                continue;
            }
            // SQL 已按 del_flag=0、status=0 过滤；这里再兜一层，保证不走库时口径也一致
            if (other.getDelFlag() != null && other.getDelFlag() == 1) {
                continue;
            }
            if (other.getStatus() != null && other.getStatus() == 1) {
                continue;
            }
            Date s2 = other.getEffStart();
            Date e2 = other.getEffEnd();
            if (s2 == null) {
                continue;
            }
            boolean s1BeforeE2 = e2 == null || s1.before(e2);
            boolean s2BeforeE1 = e1 == null || s2.before(e1);
            if (s1BeforeE2 && s2BeforeE1) {
                throw new IllegalArgumentException(
                        "线代号(" + record.getRuleCode() + ")在 "
                                + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(s2)
                                + (e2 == null ? " 起立的那条一直未让位"
                                        : " 至 " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(e2))
                                + " 的在场窗口与本条重叠；交接日只能有一套说法，旧线管到该日之前、新线从该日起头");
            }
        }
    }

    /**
     * 三个分界数缺一个这条线立不起来；后一个不比前一个大同样撑不起来。
     * 回执不能光讲"数值不对劲"：指名哪一栏空着、哪一栏没越过前头那一栏。
     */
    private void validateThresholds(TSaltIodLine r) {
        if (r.getTh1Max() == null) {
            throw new IllegalArgumentException("起算数值(th1_max)一栏空着，三条分界数缺一个，这条线立不起来");
        }
        if (r.getTh2Max() == null) {
            throw new IllegalArgumentException("够线数值(th2_max)一栏空着，三条分界数缺一个，这条线立不起来");
        }
        if (r.getTh3Max() == null) {
            throw new IllegalArgumentException("封顶数值(th3_max)一栏空着，三条分界数缺一个，这条线立不起来");
        }
        if (r.getEffStart() == null) {
            throw new IllegalArgumentException("立线之日(eff_start)一栏空着，线没有起头日子，立不起来");
        }
        if (r.getTh2Max().compareTo(r.getTh1Max()) <= 0) {
            throw new IllegalArgumentException(
                    "够线数值(th2_max)=" + r.getTh2Max() + " 没越过前头的起算数值(th1_max)=" + r.getTh1Max()
                            + "，后面那个数必须比前面大，这条线立不住");
        }
        if (r.getTh3Max().compareTo(r.getTh2Max()) <= 0) {
            throw new IllegalArgumentException(
                    "封顶数值(th3_max)=" + r.getTh3Max() + " 没越过前头的够线数值(th2_max)=" + r.getTh2Max()
                            + "，后面那个数必须比前面大，这条线立不住");
        }
        if (r.getEffEnd() != null && !r.getEffEnd().after(r.getEffStart())) {
            throw new IllegalArgumentException(
                    "让位之日(eff_end)=" + r.getEffEnd() + " 没有晚于立线之日(eff_start)=" + r.getEffStart()
                            + "，旧线管不到该日之前，这条线立不住");
        }
    }
}
