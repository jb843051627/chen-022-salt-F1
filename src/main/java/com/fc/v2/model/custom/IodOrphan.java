package com.fc.v2.model.custom;

import java.util.Date;

import com.fasterxml.jackson.annotation.JsonFormat;

/**
 * 孤儿单一条：屏上仍挂着线代号、线册里那一刻却请不出在场线的报告。
 * reason 指名请不出的缘由，便于一条条对账。
 *
 * @author fuce
 * @date 2026-10-02
 */
public class IodOrphan {

    /** 送检报告/批验单代号 */
    private String bizNo;

    /** 报告上挂着的线代号 */
    private String ruleCode;

    /** 报告自己的问话时刻 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private Date at;

    /** 请不出在场线的缘由：代号空着/查无此代号/已停用/未到立线之日/已过让位之日 */
    private String reason;

    public IodOrphan() {
    }

    public IodOrphan(String bizNo, String ruleCode, Date at, String reason) {
        this.bizNo = bizNo;
        this.ruleCode = ruleCode;
        this.at = at;
        this.reason = reason;
    }

    public String getBizNo() {
        return bizNo;
    }

    public void setBizNo(String bizNo) {
        this.bizNo = bizNo;
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

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}
