package com.fc.v2.service;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

import com.fc.v2.model.auto.TSaltIodLine;
import com.fc.v2.model.custom.IodLineValidity;
import com.fc.v2.model.custom.IodOrphan;
import com.fc.v2.model.custom.IodReportRef;
import com.fc.v2.model.custom.IodVerdict;

/**
 * 碘含量分档判定线 Service（rule-eval 形状：只读线册，无增删改）。
 *
 * 口径四处，全册只此一份，屏上名单与逐条翻册凑出来的名单都从这里出：
 * 一、一条线：起算 th1Max、够线 th2Max、封顶 th3Max 三个分界数缺一不可、且须严格
 *     递增；立线之日不可缺。碘量够不着够线记"偏低"，够着够线又压不过封顶记"合规"，
 *     压过封顶记"过档"。
 * 二、取哪条：只认问话那一刻——立线之日已够着（含）、让位之日还没到（不含）的线才
 *     在场；补算往期问的仍是往期那一刻，绝不拿最新线顶替。
 * 三、在场两条：先比让位顺位 priority（大者先说话），并列再比线代号，排法落在末位者
 *     胜出；三条都够不上，结论记"无可用线"，线代号与立线之日一并留空。已停用的线册
 *     里翻得着、屏上列得出，只是不许捞出来比数。
 * 四、存量口子：只填立线之日、让位留空的线认作一直在场；取样、封样两刻必须同用一条
 *     线，不许各挑各的。
 *
 * @author fuce
 * @date 2026-10-02
 */
public interface ITSaltIodLineService {

    /** 按主键查一条（线册翻检，停用线也查得到，但不代表在场） */
    TSaltIodLine selectTSaltIodLineById(Long id);

    /**
     * 全册翻检：未删除的线逐条列出，已停用的在册、在屏上列得出，只是不参与判档。
     * 与 {@link #listAvailable(Date)} 同一个加载口，只是不做时刻/停用过滤。
     */
    List<TSaltIodLine> listLedger();

    /**
     * 立线校验：三个分界数缺一栏、后一栏没严格越过前一栏、立线之日空着、让位之日不晚
     * 于立线之日，都立不起来。回执逐句指名字段。
     */
    IodLineValidity validate(TSaltIodLine line);

    /**
     * at 时刻在场名单（唯一出处：把线册逐条走一遍，停用、删除、未立、已让位的都剔
     * 掉），顺序即说话顺位：让位顺位降序，并列按线代号排法末位在前。
     */
    List<TSaltIodLine> listAvailable(Date at);

    /** at 时刻说话的那一条（在场且决胜第一）；一条都请不出时返回 null */
    TSaltIodLine resolveLine(Date at);

    /**
     * 按线代号请 at 时刻在场的那一条。请不出（无此代号／已停用／未立／已让位）一律
     * 返回 null——绝不退回"取最新一条"，补算往期顶替的旧错堵在这一处。
     */
    TSaltIodLine resolveLineByCode(String ruleCode, Date at);

    /**
     * 送检判档：以 at 为问话时刻，请在场说话的线比数。无可用线时结论"无可用线"，
     * ruleCode/effStart 留空，不抄旁边那条线的数字。
     */
    IodVerdict judge(BigDecimal iodine, Date at);

    /**
     * 挂代号判档：结论上已挂着线代号时，只许该代号在 at 刻在场的那条说话；该刻请
     * 不出这条在场线，按无可用线回话，不许换一条在场线顶替。
     */
    IodVerdict judgeByCode(String ruleCode, BigDecimal iodine, Date at);

    /**
     * 批验单判档：取样那一刻与封样那一刻必须落在同一条线上，两刻各请各的、请出两条
     * 不同的线即判跨线冲突（返回的回话 grade 为空、conflict 指名两条线），不许两栏各
     * 挑各的凑一个档位。两刻同线时与 {@link #judge(BigDecimal, Date)} 完全同口径。
     */
    IodVerdict judgeBatch(BigDecimal iodine, Date sampleAt, Date sealAt);

    /** 单条线在 at 刻是否在场（口径与 listAvailable 逐条过滤一致） */
    boolean usable(Long id, Date at);

    /** at 刻在场条数（角标/看板） */
    int countAvailable(Date at);

    /**
     * 孤儿单核对：屏上仍挂着线代号、线册里那一刻却请不出在场线的单子，逐条指出原因
     * （查无此代号／已停用／未到立线之日／已过让位之日）。
     */
    List<IodOrphan> findOrphans(List<IodReportRef> refs);
}
