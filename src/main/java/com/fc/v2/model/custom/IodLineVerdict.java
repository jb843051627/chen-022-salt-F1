package com.fc.v2.model.custom;

import java.util.Date;

import com.fasterxml.jackson.annotation.JsonFormat;

import com.fc.v2.model.auto.TSaltIodLine;

/**
 * 送检报告定档回话。
 * 只有一条走法：结论、线代号、立线之日三栏同出一处；
 * 无可用线时 level="无可用线"，ruleCode 与 effStart 必须留空（不抄旁边那条的数字）。
 *
 * @author fuce
 * @date 2026-10-02
 */
public class IodLineVerdict {

    /** 结论栏：偏低 / 合规 / 过档 / 无可用线 */
    private String level;

    /** 在场说话那条的线代号；无可用线时留空 */
    private String ruleCode;

    /** 在场说话那条的立线之日；无可用线时留空 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Date effStart;

    /** 报告实际比数用的碘量，仅回显对账，不参与选线 */
    private java.math.BigDecimal iodine;

    /** 问话的那一刻，仅回显对账 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Date at;

    public IodLineVerdict() {
    }

    /** 无可用线：线代号与立线之日一并留空 */
    public static IodLineVerdict none(java.math.BigDecimal iodine, Date at) {
        IodLineVerdict v = new IodLineVerdict();
        v.level = com.fc.v2.service.ITSaltIodLineService.LEVEL_NONE;
        v.iodine = iodine;
        v.at = at;
        return v;
    }

    /** 在场线已定，按三档落字 */
    public static IodLineVerdict of(TSaltIodLine line, String level, java.math.BigDecimal iodine, Date at) {
        IodLineVerdict v = new IodLineVerdict();
        v.level = level;
        v.ruleCode = line.getRuleCode();
        v.effStart = line.getEffStart();
        v.iodine = iodine;
        v.at = at;
        return v;
    }

    public String getLevel() {
        return level;
    }

    public void setLevel(String level) {
        this.level = level;
    }

    public String getRuleCode() {
        return ruleCode;
    }

    public void setRuleCode(String ruleCode) {
        this.ruleCode = ruleCode;
    }

    public Date getEffStart() {
        return effStart;
    }

    public void setEffStart(Date effStart) {
        this.effStart = effStart;
    }

    public java.math.BigDecimal getIodine() {
        return iodine;
    }

    public void setIodine(java.math.BigDecimal iodine) {
        this.iodine = iodine;
    }

    public Date getAt() {
        return at;
    }

    public void setAt(Date at) {
        this.at = at;
    }
}
