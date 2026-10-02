package com.fc.v2.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fc.v2.mapper.auto.TSaltIodLineMapper;
import com.fc.v2.model.auto.TSaltIodLine;
import com.fc.v2.model.custom.IodLineValidity;
import com.fc.v2.model.custom.IodOrphan;
import com.fc.v2.model.custom.IodReportRef;
import com.fc.v2.model.custom.IodVerdict;
import com.fc.v2.service.impl.TSaltIodLineServiceImpl;

/**
 * 碘含量分档判定线口径测试：以 doc/schema/salt.sql 的种子线为底册（mock mapper 只回
 * del_flag=0 的册，等于 SQL 那道 del_flag 过滤）。
 *
 * 六年三换：LEG-001/002 开口存量线；DJ-2020[2020-03-01,2023-03-01)、
 * DJ-2023[2023-03-01,2026-03-01)、DJ-2026[2026-03-01,∞)；DJ-STOP 已停用。
 */
@ExtendWith(MockitoExtension.class)
public class TSaltIodLineServiceImplTest {

    @Mock
    private TSaltIodLineMapper mapper;

    @InjectMocks
    private TSaltIodLineServiceImpl svc;

    private final SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    private TSaltIodLine leg1;
    private TSaltIodLine leg2;
    private TSaltIodLine dj2020;
    private TSaltIodLine dj2023;
    private TSaltIodLine dj2026;
    private TSaltIodLine stopped;
    private List<TSaltIodLine> ledger;

    private Date t(String s) {
        try {
            return f.parse(s);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private TSaltIodLine line(long id, String code, String name,
                              String th1, String th2, String th3,
                              String start, String end, int priority, int status) {
        TSaltIodLine r = new TSaltIodLine();
        r.setId(id);
        r.setRuleCode(code);
        r.setRuleName(name);
        r.setTh1Max(new BigDecimal(th1));
        r.setTh2Max(new BigDecimal(th2));
        r.setTh3Max(new BigDecimal(th3));
        r.setEffStart(t(start));
        r.setEffEnd(end == null ? null : t(end));
        r.setPriority(priority);
        r.setStatus(status);
        r.setDelFlag(0);
        return r;
    }

    @BeforeEach
    public void setup() {
        leg1 = line(100, "LEG-001", "存量甲", "2", "20", "50", "2015-01-01 00:00:00", null, 0, 0);
        leg2 = line(101, "LEG-002", "存量乙", "3", "21", "50", "2015-01-01 00:00:00", null, 0, 0);
        dj2020 = line(102, "DJ-2020", "二〇二〇版", "5", "20", "50",
                "2020-03-01 00:00:00", "2023-03-01 00:00:00", 10, 0);
        dj2023 = line(103, "DJ-2023", "二〇二三版", "5", "20", "50",
                "2023-03-01 00:00:00", "2026-03-01 00:00:00", 10, 0);
        dj2026 = line(104, "DJ-2026", "二〇二六版", "5", "21", "50",
                "2026-03-01 00:00:00", null, 10, 0);
        stopped = line(105, "DJ-STOP", "已停用加严线", "5", "18", "45",
                "2024-01-01 00:00:00", null, 100, 1);
        ledger = new ArrayList<TSaltIodLine>(Arrays.asList(leg1, leg2, dj2020, dj2023, dj2026, stopped));
        // lenient：纯立线校验的用例不碰线册，严格桩会把这根桩判成多余
        lenient().when(mapper.selectList(any())).thenReturn(ledger);
    }

    // ---------- 一、一条线的样子：三档边界 ----------

    @Test
    public void 三档边界_等号落在够着与压不过一侧() {
        Date at = t("2025-06-01 12:00:00"); // DJ-2023 在场
        assertEquals("偏低", svc.judge(new BigDecimal("4"), at).getGrade());      // 够不着起算
        assertEquals("偏低", svc.judge(new BigDecimal("5"), at).getGrade());      // 恰好起算仍够不着够线
        assertEquals("偏低", svc.judge(new BigDecimal("19.999"), at).getGrade());
        assertEquals("合规", svc.judge(new BigDecimal("20"), at).getGrade());     // 恰好够线
        assertEquals("合规", svc.judge(new BigDecimal("35"), at).getGrade());
        assertEquals("合规", svc.judge(new BigDecimal("50"), at).getGrade());     // 恰好封顶=压不过
        assertEquals("过档", svc.judge(new BigDecimal("50.001"), at).getGrade()); // 压过封顶
    }

    @Test
    public void 同一刻只有一个结论_说话线唯一() {
        IodVerdict v = svc.judge(new BigDecimal("30"), t("2025-06-01 00:00:00"));
        assertEquals("DJ-2023", v.getRuleCode());
        assertEquals("合规", v.getGrade());
        assertEquals(3, svc.listAvailable(t("2025-06-01 00:00:00")).size()); // LEG×2 + DJ-2023
    }

    // ---------- 二、取哪一条：问话时刻 / 交接日含起不含止 ----------

    @Test
    public void 窗口含立线之日_不含让位之日() {
        Date handover = t("2023-03-01 00:00:00");
        assertNotNull(svc.resolveLineByCode("DJ-2023", handover)); // 新线从交接日起头
        assertNull(svc.resolveLineByCode("DJ-2020", handover));    // 旧线管到该日之前
        Date handover2 = t("2026-03-01 00:00:00");
        assertNotNull(svc.resolveLineByCode("DJ-2026", handover2));
        assertNull(svc.resolveLineByCode("DJ-2023", handover2));
    }

    @Test
    public void 交接日两个入口各问一遍回话一模一样() {
        Date at = t("2023-03-01 00:00:00");
        BigDecimal x = new BigDecimal("30");
        assertEquals(svc.judge(x, at).getGrade(), svc.judgeByCode("DJ-2023", x, at).getGrade());
        assertTrue(svc.usable(103L, at));
        assertFalse(svc.usable(102L, at));
    }

    @Test
    public void 补算往期问的是往期那一刻_不许新线顶替() {
        Date past = t("2025-03-15 10:00:00");
        BigDecimal x = new BigDecimal("20.5");
        // 去年三月在 DJ-2023 档内本是合规
        assertEquals("合规", svc.judge(x, past).getGrade());
        assertEquals("DJ-2023", svc.judge(x, past).getRuleCode());
        // 挂着今年新线代号判去年三月：该刻 DJ-2026 未立，请不出在场线，绝不退回取最新
        IodVerdict wrong = svc.judgeByCode("DJ-2026", x, past);
        assertEquals("无可用线", wrong.getGrade());
        assertNull(wrong.getRuleCode());
        assertNull(wrong.getEffStart());
        // 同一数值在眼下新线下确实低一格——但只许用于眼下的报告
        Date now = t("2026-06-01 10:00:00");
        assertEquals("偏低", svc.judge(x, now).getGrade());
        assertEquals("DJ-2026", svc.judge(x, now).getRuleCode());
    }

    // ---------- 三、在场两条：让位顺位 + 代号末位；停用排除；无可用线留空 ----------

    @Test
    public void 让位顺位高者先说话_停用线顺位再高也不参与() {
        Date at = t("2025-06-01 00:00:00");
        // DJ-2023(p10) 压过 LEG(p0)；DJ-STOP 虽 p100 但已停用，被剔
        assertEquals("DJ-2023", svc.resolveLine(at).getRuleCode());
        assertNull(svc.resolveLineByCode("DJ-STOP", at));
        assertFalse(svc.usable(105L, at));
        assertTrue(svc.listAvailable(at).stream().noneMatch(r -> "DJ-STOP".equals(r.getRuleCode())));
        // 册里翻得着、屏上列得出
        assertTrue(svc.listLedger().stream().anyMatch(r -> "DJ-STOP".equals(r.getRuleCode())));
    }

    @Test
    public void 顺位相同按线代号排法末位胜出() {
        Date early = t("2018-01-01 00:00:00"); // 只有两条开口存量线在场，顺位都是 0
        List<TSaltIodLine> avail = svc.listAvailable(early);
        assertEquals(2, avail.size());
        assertEquals("LEG-002", avail.get(0).getRuleCode()); // 末位在前
        assertEquals("LEG-002", svc.resolveLine(early).getRuleCode());
    }

    @Test
    public void 三条都够不上_结论无可用线_代号与立线之日留空() {
        Date beforeAll = t("2010-01-01 00:00:00");
        IodVerdict v = svc.judge(new BigDecimal("30"), beforeAll);
        assertEquals("无可用线", v.getGrade());
        assertNull(v.getRuleCode());
        assertNull(v.getEffStart());
        assertEquals(0, svc.countAvailable(beforeAll));
    }

    // ---------- 四、存量口子：让位留空一直在场；取样封样同线 ----------

    @Test
    public void 让位留空认作一直在场() {
        assertTrue(svc.usable(100L, t("2018-01-01 00:00:00")));
        assertTrue(svc.usable(100L, t("2030-01-01 00:00:00")));
        // 远未来开口存量线仍在场，只是顺位低于 DJ-2026，说话的是 DJ-2026
        assertEquals("DJ-2026", svc.resolveLine(t("2030-01-01 00:00:00")).getRuleCode());
    }

    @Test
    public void 取样封样同刻段_同用一条线() {
        IodVerdict v = svc.judgeBatch(new BigDecimal("30"),
                t("2025-03-01 09:00:00"), t("2025-04-01 09:00:00"));
        assertFalse(v.isConflict());
        assertEquals("DJ-2023", v.getRuleCode());
        assertEquals("合规", v.getGrade());
    }

    @Test
    public void 取样封样跨过交接日_请出两条线即冲突不给档位() {
        IodVerdict v = svc.judgeBatch(new BigDecimal("30"),
                t("2026-02-28 09:00:00"), t("2026-03-02 09:00:00"));
        assertTrue(v.isConflict());
        assertNull(v.getGrade());
        assertEquals("DJ-2023", v.getRuleCode());
        assertEquals("DJ-2026", v.getOtherRuleCode());
    }

    @Test
    public void 取样封样两刻都无线_按无可用线而非冲突() {
        IodVerdict v = svc.judgeBatch(new BigDecimal("30"),
                t("2010-01-01 00:00:00"), t("2010-02-01 00:00:00"));
        assertFalse(v.isConflict());
        assertEquals("无可用线", v.getGrade());
        assertNull(v.getRuleCode());
    }

    // ---------- 立线校验：缺栏 / 后不越过前，逐栏指名 ----------

    @Test
    public void 完整且严格递增_立得住() {
        assertTrue(svc.validate(dj2023).isValid());
    }

    @Test
    public void 缺分界数_指名哪一栏空着() {
        TSaltIodLine r = line(1, "X", "x", "5", "20", "50", "2025-01-01 00:00:00", null, 1, 0);
        r.setTh2Max(null);
        IodLineValidity v = svc.validate(r);
        assertFalse(v.isValid());
        assertTrue(v.getFieldErrors().containsKey("th2Max"));
        assertTrue(v.getFieldErrors().get("th2Max").contains("够线数值"));
        assertTrue(v.getErrors().get(0).contains("空着"));
    }

    @Test
    public void 后一栏没越过前一栏_指名哪两栏() {
        TSaltIodLine r = line(1, "X", "x", "5", "20", "50", "2025-01-01 00:00:00", null, 1, 0);
        r.setTh2Max(new BigDecimal("5")); // 与起算相等，没越过
        IodLineValidity v = svc.validate(r);
        assertFalse(v.isValid());
        assertTrue(v.getFieldErrors().get("th2Max").contains("没有越过起算数值"));

        TSaltIodLine r2 = line(1, "X", "x", "5", "20", "50", "2025-01-01 00:00:00", null, 1, 0);
        r2.setTh3Max(new BigDecimal("19")); // 比够线还小
        assertTrue(svc.validate(r2).getFieldErrors().get("th3Max").contains("没有越过够线数值"));
    }

    @Test
    public void 立线之日空_或让位不晚于立线_立不住() {
        TSaltIodLine r = line(1, "X", "x", "5", "20", "50", "2025-01-01 00:00:00", null, 1, 0);
        r.setEffStart(null);
        assertTrue(svc.validate(r).getFieldErrors().containsKey("effStart"));

        TSaltIodLine r2 = line(1, "X", "x", "5", "20", "50",
                "2025-01-01 00:00:00", "2025-01-01 00:00:00", 1, 0);
        assertTrue(svc.validate(r2).getFieldErrors().containsKey("effEnd"));
    }

    @Test
    public void 立不住的线不许开口() {
        // 缺封顶的存量脏线，时间窗口在、也在册，但判档时不得请出
        TSaltIodLine broken = line(106, "BROKEN", "残缺线", "5", "20", "50",
                "2024-01-01 00:00:00", null, 99, 0);
        broken.setTh3Max(null);
        ledger.add(broken);
        Date at = t("2025-06-01 00:00:00");
        assertNull(svc.resolveLineByCode("BROKEN", at)); // 顺位 99 也不许说话
        assertFalse(svc.usable(106L, at));
    }

    // ---------- 孤儿单核对 ----------

    @Test
    public void 孤儿单逐条指出缘由() {
        List<IodReportRef> refs = Arrays.asList(
                new IodReportRef("BJ1", "DJ-2026", t("2025-03-15 10:00:00")), // 新线未立
                new IodReportRef("BJ2", "DJ-STOP", t("2025-03-15 10:00:00")), // 已停用
                new IodReportRef("BJ3", "GHOST", t("2025-03-15 10:00:00")),   // 查无此线
                new IodReportRef("BJ4", "DJ-2020", t("2025-03-15 10:00:00")), // 已让位
                new IodReportRef("BJ5", "", t("2025-03-15 10:00:00")),        // 代号空着
                new IodReportRef("BJ6", "DJ-2023", t("2025-03-15 10:00:00"))  // 正常，不是孤儿
        );
        List<IodOrphan> orphans = svc.findOrphans(refs);
        assertEquals(5, orphans.size());
        assertEquals("BJ1", orphans.get(0).getBizNo());
        assertTrue(orphans.get(0).getReason().contains("立线之日"));
        assertTrue(orphans.get(1).getReason().contains("停用"));
        assertTrue(orphans.get(2).getReason().contains("查无代号"));
        assertTrue(orphans.get(3).getReason().contains("让位之日"));
        assertTrue(orphans.get(4).getReason().contains("代号空着"));
        assertTrue(orphans.stream().noneMatch(o -> "BJ6".equals(o.getBizNo())));
    }
}
