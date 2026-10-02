package com.fc.v2.model.custom;

import java.math.BigDecimal;
import java.util.Date;

import com.fasterxml.jackson.annotation.JsonFormat;

/**
 * 一份送检报告按问话时刻在碘含量分档判定线上判出来的回话。
 *
 * 结论栏 grade 只许是三档之一：偏低 / 合规 / 过档；三条线都够不上在场时
 * grade 记"无可用线"，此时 ruleCode（线代号）与 effStart（立线之日）一并留空，
 * 不许抄旁边那条线的数字凑数。
 *
 * @author fuce
 * @date 2026-10-02
 */
public class IodVerdict {

    /** 结论栏：偏低 / 合规 / 过档 / 无可用线 */
    private String grade;

    /** 说话的线代号；无可用线时留空 */
    private String ruleCode;

    /** 说话线的立线之日；无可用线时留空 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Date effStart;

    /** 说话线主键（回执核对用，结论不挂它） */
    private Long ruleId;

    /** 说话线线名（屏上展示） */
    private String ruleName;

    /** 问话时刻 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Date at;

    /** 送检碘量（毫克每千克，回话回显） */
    private BigDecimal iodine;

    /** 批验单两刻是否落在两条线上（取样、封样各请各的，冲突时不给档位） */
    private boolean conflict;

    /** 冲突时另一刻请到的另一条线代号（无冲突留空） */
    private String otherRuleCode;

    /** 冲突时另一条线的立线之日 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Date otherEffStart;

    public IodVerdict() {
    }

    public IodVerdict(String grade, String ruleCode, Date effStart, Long ruleId, String ruleName,
                      Date at, BigDecimal iodine) {
        this.grade = grade;
        this.ruleCode = ruleCode;
        this.effStart = effStart;
        this.ruleId = ruleId;
        this.ruleName = ruleName;
        this.at = at;
        this.iodine = iodine;
    }

    public String getGrade() {
        return grade;
    }

    public void setGrade(String grade) {
        this.grade = grade;
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

    public Long getRuleId() {
        return ruleId;
    }

    public void setRuleId(Long ruleId) {
        this.ruleId = ruleId;
    }

    public String getRuleName() {
        return ruleName;
    }

    public void setRuleName(String ruleName) {
        this.ruleName = ruleName;
    }

    public Date getAt() {
        return at;
    }

    public void setAt(Date at) {
        this.at = at;
    }

    public BigDecimal getIodine() {
        return iodine;
    }

    public void setIodine(BigDecimal iodine) {
        this.iodine = iodine;
    }

    public boolean isConflict() {
        return conflict;
    }

    public void setConflict(boolean conflict) {
        this.conflict = conflict;
    }

    public String getOtherRuleCode() {
        return otherRuleCode;
    }

    public void setOtherRuleCode(String otherRuleCode) {
        this.otherRuleCode = otherRuleCode;
    }

    public Date getOtherEffStart() {
        return otherEffStart;
    }

    public void setOtherEffStart(Date otherEffStart) {
        this.otherEffStart = otherEffStart;
    }
}
