package com.fc.v2.service;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

import com.fc.v2.model.auto.TSaltIodLine;
import com.fc.v2.model.custom.IodLineVerdict;

/**
 * 碘含量分档判定线 Service接口（rule-eval 形状：线册翻检、在场定位、送检报告定档）
 *
 * 在场口径（处处一致）：
 *   立线之日 effStart 已够着（effStart &lt;= at）、让位之日 effEnd 还没到（effEnd 为空认作一直未让位，
 *   否则 at &lt; effEnd，交接当日归新线）；且 del_flag=0、status=0（已停用只供翻检，不参与判定）。
 * 多条同时在场时：让位顺位 priority 数值大的先说话，并列再比线代号 ruleCode，照字符串排法落在末位者胜出。
 *
 * @author fuce
 * @date 2026-10-02
 */
public interface ITSaltIodLineService {

    /** 档位：偏低（够不着够线） */
    String LEVEL_LOW = "偏低";
    /** 档位：合规（够着够线又压不过封顶，边界值归本档） */
    String LEVEL_OK = "合规";
    /** 档位：过档（压过封顶） */
    String LEVEL_OVER = "过档";
    /** 三条都够不上时结论栏落字；线代号与立线之日一并留空 */
    String LEVEL_NONE = "无可用线";

    /** 按主键翻检（已停用的也翻得着） */
    TSaltIodLine selectTSaltIodLineById(Long id);

    /**
     * 线册全册：逐条列得出，含已停用（status=1）的线；只剔除删除标记 del_flag=1。
     * 屏上"线册"列表与翻检共用此出口。
     */
    List<TSaltIodLine> listBook();

    /**
     * at 那一刻的在场名单，已按说话次序排好（让位顺位降序，并列线代号降序）。
     * 定档、屏上在场名单、countAvailable、悬空甄别都由此一条凑出，出处同一处。
     */
    List<TSaltIodLine> listAvailable(Date at);

    /** at 那一刻在场条数（角标 / 看板，口径同 {@link #listAvailable(Date)}） */
    int countAvailable(Date at);

    /** 单条线在 at 那一刻是否在场，口径同 {@link #listAvailable(Date)} */
    boolean usable(Long id, Date at);

    /**
     * 按线代号回线册，请 at 那一刻在场的那一条；不在册（含代号写错、已删除）或那一刻请不出在场线，
     * 一律返回 null——结论上挂着代号、册里请不出在场线的旧坑由这里挡住。
     */
    TSaltIodLine resolveByCode(String ruleCode, Date at);

    /**
     * 定档：以 at 那一刻在场的线（取样、封样同用此一条）比 iodine 数值。
     * 无在场线时结论为"无可用线"，ruleCode 与 effStart 两栏一并留空，不许拿旁边的线凑数。
     *
     * @param iodine 报告碘量（毫克每千克）
     * @param at     问话的那一刻（补算往期即往期自己那一刻）
     */
    IodLineVerdict judge(BigDecimal iodine, Date at);

    /**
     * 按指定线代号定档：代号那一刻不在场（含已停用）时按"无可用线"回话，结论不缀该线。
     */
    IodLineVerdict judgeByCode(String ruleCode, BigDecimal iodine, Date at);

    /**
     * 屏上仍挂着线代号、线册里 at 那一刻却请不出在场线的单子，逐条指出。
     * 入参为屏上各单（线代号, 问话那一刻）；返回的每条原样带回批次号以便对账。
     */
    List<DanglingRef> findDangling(List<DanglingRef> entries);

    /** 立线（新增）。三个分界数缺一立不起来，后一个不大于前一个也立不住；回执指名哪一栏。 */
    int insertTSaltIodLine(TSaltIodLine record);

    /** 改线（保存）。校验口径同新增，按主键更新，已停用的线也允许改正。 */
    int updateTSaltIodLine(TSaltIodLine record);

    /** 停用：册里翻得着、屏上列得出，只是此后不再捞它出来比数 */
    int deactivate(Long id);

    /** 删除（del_flag 置 1），支持逗号串 */
    int deleteTSaltIodLineByIds(String ids);

    /**
     * 悬空单：屏上挂着线代号的送检单，与线册在某一刻对账用。
     */
    class DanglingRef {
        /** 送检单/批次代号，仅用于回指，不参与判定 */
        private String billNo;
        /** 屏上挂的线代号 */
        private String ruleCode;
        /** 问话的那一刻（取样或封样时刻，两者必须同用一条线） */
        private Date at;

        public DanglingRef() {
        }

        public DanglingRef(String billNo, String ruleCode, Date at) {
            this.billNo = billNo;
            this.ruleCode = ruleCode;
            this.at = at;
        }

        public String getBillNo() {
            return billNo;
        }

        public void setBillNo(String billNo) {
            this.billNo = billNo;
        }

        public String getRuleCode() {
            return ruleCode;
        }

        public void setRuleCode(String ruleCode) {
            this.ruleCode = ruleCode;
        }

        public Date getAt() {
            return at;
        }

        public void setAt(Date at) {
            this.at = at;
        }
    }
}
