package com.fc.v2.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fc.v2.mapper.auto.TSaltIodLineMapper;
import com.fc.v2.model.auto.TSaltIodLine;
import com.fc.v2.model.custom.IodLineVerdict;
import com.fc.v2.service.ITSaltIodLineService;
import com.fc.v2.service.ITSaltIodLineService.DanglingRef;

/**
 * 碘含量分档判定线 Service 单测：
 * 交接日半开区间、补算往期不顶替、双在场决胜、停用排除、无可用线留空、
 * 存量空让位栏、分界数具名回执、边界归合规、悬空甄别。
 */
@ExtendWith(MockitoExtension.class)
class TSaltIodLineServiceImplTest {

    @Mock
    private TSaltIodLineMapper mapper;

    @InjectMocks
    private TSaltIodLineServiceImpl service;

    private static Date t(String s) {
        try {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(s);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static TSaltIodLine line(long id, String code, int priority, int status,
                                     String start, String end) {
        TSaltIodLine r = new TSaltIodLine();
        r.setId(id);
        r.setRuleCode(code);
        r.setPriority(priority);
        r.setStatus(status);
        r.setDelFlag(0);
        r.setEffStart(t(start));
        if (end != null) {
            r.setEffEnd(t(end));
        }
        r.setTh1Max(new BigDecimal("10"));
        r.setTh2Max(new BigDecimal("20"));
        r.setTh3Max(new BigDecimal("30"));
        return r;
    }

    private void stubBook(TSaltIodLine... lines) {
        when(mapper.selectList(any())).thenReturn(new ArrayList<TSaltIodLine>(Arrays.asList(lines)));
    }

    // 二、交接那一日只有一套说法：旧线管到该日之前，新线从该日起头（窗口半开 [start, end)）
    @Test
    void handoverDayBelongsToNewLine() {
        TSaltIodLine oldLine = line(1, "A", 5, 0, "2018-01-01 00:00:00", "2020-01-01 00:00:00");
        TSaltIodLine newLine = line(2, "A", 5, 0, "2020-01-01 00:00:00", null);
        stubBook(oldLine, newLine);

        // 让位之日前一刻：旧线在场
        TSaltIodLine before = service.resolveByCode("A", t("2019-12-31 23:59:59"));
        assertSame(oldLine, before);
        // 交接当日 00:00:00：旧线已不含、新线起头，恰好一条
        TSaltIodLine onDay = service.resolveByCode("A", t("2020-01-01 00:00:00"));
        assertSame(newLine, onDay);
        assertEquals(1, service.listAvailable(t("2020-01-01 00:00:00")).size());
        // 立线之日之前：新线还没够着
        assertNull(service.resolveByCode("A", t("2017-12-31 00:00:00")));
    }

    // 二、补算往期问的是往期自己那一刻：新线不许拿去判去年三月
    @Test
    void retroactiveUsesPastLineNotNewest() {
        TSaltIodLine y2018 = line(1, "STD", 5, 0, "2018-03-01 00:00:00", "2020-03-01 00:00:00");
        TSaltIodLine y2020 = line(2, "STD", 5, 0, "2020-03-01 00:00:00", "2024-03-01 00:00:00");
        TSaltIodLine y2024 = line(3, "STD", 5, 0, "2024-03-01 00:00:00", null);
        // 新线阈值整体抬高，若被顶替去判旧报告会错档
        y2024.setTh1Max(new BigDecimal("20"));
        y2024.setTh2Max(new BigDecimal("25"));
        y2024.setTh3Max(new BigDecimal("40"));
        stubBook(y2018, y2020, y2024);

        IodLineVerdict v = service.judge(new BigDecimal("25"), t("2019-03-15 10:00:00"));
        assertEquals(ITSaltIodLineService.LEVEL_OK, v.getLevel());
        assertEquals("STD", v.getRuleCode());
        assertEquals(y2018.getEffStart(), v.getEffStart());
    }

    // 三、在场两条：先比让位顺位，顺位高的说话
    @Test
    void twoPresentHigherPrioritySpeaks() {
        TSaltIodLine low = line(1, "CODE-L", 1, 0, "2020-01-01 00:00:00", null);
        TSaltIodLine high = line(2, "CODE-H", 9, 0, "2020-01-01 00:00:00", null);
        stubBook(low, high);

        List<TSaltIodLine> present = service.listAvailable(t("2026-10-02 00:00:00"));
        assertSame(high, present.get(0));
        IodLineVerdict v = service.judge(new BigDecimal("25"), t("2026-10-02 00:00:00"));
        assertEquals("CODE-H", v.getRuleCode());
    }

    // 三、顺位相同再比线代号，照字符串排法落在末位的胜出
    @Test
    void samePriorityLastCodeWins() {
        TSaltIodLine a = line(1, "LINE-A", 5, 0, "2020-01-01 00:00:00", null);
        TSaltIodLine b = line(2, "LINE-B", 5, 0, "2020-01-01 00:00:00", null);
        stubBook(a, b);

        IodLineVerdict v = service.judge(new BigDecimal("25"), t("2026-10-02 00:00:00"));
        assertEquals("LINE-B", v.getRuleCode());
        // 同一份报告从两个入口各问一遍，回话一模一样
        IodLineVerdict v2 = service.judgeByCode("LINE-B", new BigDecimal("25"), t("2026-10-02 00:00:00"));
        assertEquals(v.getLevel(), v2.getLevel());
        assertEquals(v.getRuleCode(), v2.getRuleCode());
    }

    // 三、已停用：册里翻得着，只是不许捞出来比数
    @Test
    void deactivatedLineDoesNotJudge() {
        TSaltIodLine dead = line(1, "DEAD", 9, 1, "2020-01-01 00:00:00", null);
        stubBook(dead);
        when(mapper.selectById(1L)).thenReturn(dead);
        assertTrue(service.listAvailable(t("2026-10-02 00:00:00")).isEmpty());
        assertNull(service.resolveByCode("DEAD", t("2026-10-02 00:00:00")));
        IodLineVerdict v = service.judge(new BigDecimal("25"), t("2026-10-02 00:00:00"));
        assertEquals(ITSaltIodLineService.LEVEL_NONE, v.getLevel());
        assertNull(v.getRuleCode());
        assertNull(v.getEffStart());
        assertFalse(service.usable(1L, t("2026-10-02 00:00:00")));
    }

    // 三、三条都够不上：无可用线，线代号与立线之日一并留空，不抄旁边的数字
    @Test
    void noLinePresentLeavesCodeAndStartBlank() {
        TSaltIodLine future = line(1, "FUTURE", 9, 0, "2027-01-01 00:00:00", null);
        TSaltIodLine past = line(2, "PAST", 9, 0, "2010-01-01 00:00:00", "2015-01-01 00:00:00");
        stubBook(future, past);
        IodLineVerdict v = service.judge(new BigDecimal("25"), t("2026-10-02 00:00:00"));
        assertEquals(ITSaltIodLineService.LEVEL_NONE, v.getLevel());
        assertNull(v.getRuleCode());
        assertNull(v.getEffStart());
        assertEquals(0, service.countAvailable(t("2026-10-02 00:00:00")));
    }

    // 四、存量线：让位栏空着认作一直在场
    @Test
    void legacyLineWithBlankEffEndStaysPresent() {
        TSaltIodLine legacy = line(1, "OLD-HAND", 0, 0, "1996-05-01 00:00:00", null);
        stubBook(legacy);
        when(mapper.selectById(1L)).thenReturn(legacy);
        assertTrue(service.usable(1L, t("2026-10-02 00:00:00")));
        assertEquals(1, service.countAvailable(t("2026-10-02 00:00:00")));
    }

    // 一、分档边界：够不着够线=偏低；等于够线/等于封顶=合规；压过封顶=过档
    @Test
    void levelBoundaries() {
        TSaltIodLine r = line(1, "B", 5, 0, "2020-01-01 00:00:00", null);
        stubBook(r);
        Date at = t("2026-10-02 00:00:00");
        assertEquals(ITSaltIodLineService.LEVEL_LOW, service.judge(new BigDecimal("19.999"), at).getLevel());
        assertEquals(ITSaltIodLineService.LEVEL_OK, service.judge(new BigDecimal("20"), at).getLevel());
        assertEquals(ITSaltIodLineService.LEVEL_OK, service.judge(new BigDecimal("30"), at).getLevel());
        assertEquals(ITSaltIodLineService.LEVEL_OVER, service.judge(new BigDecimal("30.001"), at).getLevel());
    }

    // 一、三个分界数缺一个立不起来：回执指名哪一栏空着
    @Test
    void missingThresholdNamedInReceipt() {
        TSaltIodLine r = line(1, "X", 5, 0, "2026-01-01 00:00:00", null);
        r.setTh1Max(null);
        IllegalArgumentException e1 = assertThrows(IllegalArgumentException.class,
                () -> service.insertTSaltIodLine(r));
        assertTrue(e1.getMessage().contains("起算数值(th1_max)"), e1.getMessage());

        r.setTh1Max(new BigDecimal("10"));
        r.setTh2Max(null);
        IllegalArgumentException e2 = assertThrows(IllegalArgumentException.class,
                () -> service.insertTSaltIodLine(r));
        assertTrue(e2.getMessage().contains("够线数值(th2_max)"), e2.getMessage());

        r.setTh2Max(new BigDecimal("20"));
        r.setTh3Max(null);
        IllegalArgumentException e3 = assertThrows(IllegalArgumentException.class,
                () -> service.insertTSaltIodLine(r));
        assertTrue(e3.getMessage().contains("封顶数值(th3_max)"), e3.getMessage());

        r.setTh3Max(new BigDecimal("30"));
        r.setEffStart(null);
        IllegalArgumentException e4 = assertThrows(IllegalArgumentException.class,
                () -> service.insertTSaltIodLine(r));
        assertTrue(e4.getMessage().contains("立线之日(eff_start)"), e4.getMessage());
    }

    // 一、后面的数不比前面大撑不起来：指名哪一栏没越过前头
    @Test
    void thresholdsMustStrictlyIncrease() {
        TSaltIodLine r = line(1, "X", 5, 0, "2026-01-01 00:00:00", null);
        r.setTh2Max(new BigDecimal("10")); // 等于 th1
        IllegalArgumentException e1 = assertThrows(IllegalArgumentException.class,
                () -> service.insertTSaltIodLine(r));
        assertTrue(e1.getMessage().contains("够线数值(th2_max)") && e1.getMessage().contains("起算数值(th1_max)"),
                e1.getMessage());

        r.setTh2Max(new BigDecimal("20"));
        r.setTh3Max(new BigDecimal("19")); // 小于 th2
        IllegalArgumentException e2 = assertThrows(IllegalArgumentException.class,
                () -> service.insertTSaltIodLine(r));
        assertTrue(e2.getMessage().contains("封顶数值(th3_max)") && e2.getMessage().contains("够线数值(th2_max)"),
                e2.getMessage());
    }

    // 二、同代号窗口重叠不许；首尾相接（旧 effEnd = 新 effStart）可以
    @Test
    void sameCodeWindowOverlapRejectedButTouchingAllowed() {
        TSaltIodLine oldLine = line(1, "STD", 5, 0, "2020-01-01 00:00:00", "2024-01-01 00:00:00");
        when(mapper.selectList(any())).thenReturn(new ArrayList<TSaltIodLine>(Arrays.asList(oldLine)));
        when(mapper.insert(any())).thenReturn(1);

        TSaltIodLine overlap = line(0, "STD", 5, 0, "2023-12-31 00:00:00", null);
        assertThrows(IllegalArgumentException.class, () -> service.insertTSaltIodLine(overlap));

        TSaltIodLine touch = line(0, "STD", 5, 0, "2024-01-01 00:00:00", null);
        assertEquals(1, service.insertTSaltIodLine(touch));

        // 已停用的旧线不挡新线
        oldLine.setStatus(1);
        TSaltIodLine overDead = line(0, "STD", 5, 0, "2022-01-01 00:00:00", null);
        assertEquals(1, service.insertTSaltIodLine(overDead));
    }

    // 三、屏上挂着代号、册里那一刻请不出在场线的单子，一条条指出
    @Test
    void danglingRefsListedOneByOne() {
        TSaltIodLine only2024 = line(1, "STD-2024", 5, 0, "2024-03-01 00:00:00", null);
        stubBook(only2024);

        List<DanglingRef> entries = Arrays.asList(
                new DanglingRef("R-001", "STD-2024", t("2025-03-01 00:00:00")), // 在场，不悬空
                new DanglingRef("R-002", "STD-2024", t("2024-02-28 23:59:59")), // 立线前，悬空
                new DanglingRef("R-003", "STD-2012", t("2025-03-01 00:00:00")), // 代号查无，悬空
                new DanglingRef("R-004", null, t("2025-03-01 00:00:00")));       // 代号空着，悬空
        List<DanglingRef> dangling = service.findDangling(entries);
        assertEquals(3, dangling.size());
        assertEquals("R-002", dangling.get(0).getBillNo());
        assertEquals("R-003", dangling.get(1).getBillNo());
        assertEquals("R-004", dangling.get(2).getBillNo());
    }

    // 二、按代号问话时请不出在场线：结论落无可用线，且不缀该代号
    @Test
    void judgeByCodeMissingLineReturnsNoneWithoutCode() {
        stubBook(line(1, "OTHER", 5, 0, "2024-03-01 00:00:00", null));
        IodLineVerdict v = service.judgeByCode("STD-2012", new BigDecimal("25"), t("2025-03-01 00:00:00"));
        assertEquals(ITSaltIodLineService.LEVEL_NONE, v.getLevel());
        assertNull(v.getRuleCode());
        assertNull(v.getEffStart());
    }

    // 让位之日不晚于立线之日立不住
    @Test
    void effEndMustBeAfterEffStart() {
        TSaltIodLine r = line(1, "X", 5, 0, "2026-01-01 00:00:00", "2025-12-31 00:00:00");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.insertTSaltIodLine(r));
        assertTrue(e.getMessage().contains("让位之日(eff_end)"), e.getMessage());
    }
}
