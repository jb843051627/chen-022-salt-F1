package com.fc.v2.controller.admin;

import java.math.BigDecimal;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.ModelMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import com.fc.v2.common.base.BaseController;
import com.fc.v2.common.domain.AjaxResult;
import com.fc.v2.model.auto.TSaltIodLine;
import com.fc.v2.model.custom.IodLineValidity;
import com.fc.v2.model.custom.IodOrphan;
import com.fc.v2.model.custom.IodReportRef;
import com.fc.v2.model.custom.IodVerdict;
import com.fc.v2.service.ITSaltIodLineService;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;

/**
 * 碘含量分档判定线 Controller（rule-eval 形状：线册只读 + 送检判档）。
 *
 * 屏上一切口径都取自 ITSaltIodLineService 那一处：在场名单、判档、孤儿单核对不另摆
 * 一套。新线只从立线之日起开口，旧线管到让位之日之前，补算往期问的是往期那一刻。
 *
 * @author fuce
 * @date 2026-10-02
 */
@Api(value = "碘含量分档判定线")
@Controller
@RequestMapping("/SaltIodLineController")
public class SaltIodLineController extends BaseController {

    private final String prefix = "admin/saltIodLine";

    /**
     * 线册时刻与读库同一帧：JDBC URL 钉的是 serverTimezone=GMT，问话时刻也按 GMT 墙钟
     * 解析成瞬间，避免 InitBinder 走 JVM 默认时区（部署在 GMT+8 时）与读库帧错位 8 小
     * 时、在交接当日错档（旧实现踩过的坑）。service 内部一律比瞬间毫秒，不再把 Date 绑
     * 进 SQL，两头都堵死。
     */
    private static Date parseAt(String s) {
        if (s == null || s.trim().isEmpty()) {
            return null;
        }
        SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        df.setLenient(false);
        df.setTimeZone(TimeZone.getTimeZone("GMT"));
        try {
            return df.parse(s.trim());
        } catch (ParseException e) {
            throw new IllegalArgumentException("问话时刻得是 yyyy-MM-dd HH:mm:ss 的墙钟时刻：" + s, e);
        }
    }

    @Autowired
    private ITSaltIodLineService saltIodLineService;

    @ApiOperation(value = "判档页跳转", notes = "判档页跳转")
    @GetMapping("/view")
    public String view(ModelMap model) {
        return prefix + "/list";
    }

    @ApiOperation(value = "全册翻检", notes = "未删除的线逐条列出，含已停用线（在册、可列，不参与判档）")
    @GetMapping("/ledger")
    @ResponseBody
    public List<TSaltIodLine> ledger() {
        return saltIodLineService.listLedger();
    }

    @ApiOperation(value = "某刻在场名单", notes = "把线册逐条走一遍，只留 at 刻在场的线，按说话顺位排序")
    @GetMapping("/available")
    @ResponseBody
    public AjaxResult available(String at) {
        Date moment = parseAt(at);
        if (moment == null) {
            return AjaxResult.error("问话时刻（at）空着，无法请在场线");
        }
        return AjaxResult.successData(200, saltIodLineService.listAvailable(moment));
    }

    @ApiOperation(value = "送检判档", notes = "按 at 刻在场说话的线判档；带 ruleCode 时只许该代号在 at 刻在场的线说话")
    @GetMapping("/judge")
    @ResponseBody
    public AjaxResult judge(BigDecimal iodine, String at, String ruleCode) {
        Date moment = parseAt(at);
        if (moment == null) {
            return AjaxResult.error("问话时刻（at）空着，无法判档");
        }
        if (iodine == null) {
            return AjaxResult.error("送检碘量（iodine）空着，无法比数");
        }
        IodVerdict verdict = (ruleCode == null || ruleCode.trim().isEmpty())
                ? saltIodLineService.judge(iodine, moment)
                : saltIodLineService.judgeByCode(ruleCode.trim(), iodine, moment);
        return AjaxResult.successData(200, verdict);
    }

    @ApiOperation(value = "批验单判档", notes = "取样、封样两刻须同用一条线；请出两条不同的线即回跨线冲突")
    @GetMapping("/judgeBatch")
    @ResponseBody
    public AjaxResult judgeBatch(BigDecimal iodine, String sampleAt, String sealAt) {
        if (iodine == null) {
            return AjaxResult.error("送检碘量（iodine）空着，无法比数");
        }
        Date sample = parseAt(sampleAt);
        Date seal = parseAt(sealAt);
        if (sample == null || seal == null) {
            return AjaxResult.error("取样时刻、封样时刻两栏都得填，不许各挑各的");
        }
        IodVerdict verdict = saltIodLineService.judgeBatch(iodine, sample, seal);
        if (verdict.isConflict()) {
            return AjaxResult.error(501, "取样那一刻与封样那一刻落在两条线上：取样用 "
                    + verdict.getRuleCode() + "，封样用 " + verdict.getOtherRuleCode()
                    + "，两栏不许各挑各的，不给档位").put("data", verdict);
        }
        return AjaxResult.successData(200, verdict);
    }

    @ApiOperation(value = "立线校验", notes = "三个分界数缺栏/后不越过前、立线之日空、让位不晚于立线，逐栏指名回执")
    @PostMapping("/validate")
    @ResponseBody
    public AjaxResult validate(TSaltIodLine line) {
        IodLineValidity validity = saltIodLineService.validate(line);
        if (validity.isValid()) {
            return AjaxResult.success("这条线立得住").put("data", validity);
        }
        return AjaxResult.error(String.join("；", validity.getErrors())).put("data", validity);
    }

    @ApiOperation(value = "单条在场核验", notes = "某条线在 at 刻是否在场（口径与名单逐条过滤一致）")
    @GetMapping("/usable")
    @ResponseBody
    public AjaxResult usable(Long id, Date at) {
        if (id == null || at == null) {
            return AjaxResult.error("线主键（id）与问话时刻（at）都得填");
        }
        return AjaxResult.successData(200, saltIodLineService.usable(id, at));
    }

    @ApiOperation(value = "孤儿单核对", notes = "屏上仍挂着线代号、线册里那一刻却请不出在场线的单子，逐条指出")
    @PostMapping("/orphans")
    @ResponseBody
    public AjaxResult orphans(@RequestBody List<IodReportRef> refs) {
        List<IodOrphan> orphans = saltIodLineService.findOrphans(refs);
        return AjaxResult.successData(200, orphans);
    }

    /** 问话时刻格式不对时回干净的话，不抛 500 页 */
    @org.springframework.web.bind.annotation.ExceptionHandler(IllegalArgumentException.class)
    @ResponseBody
    public AjaxResult badMoment(IllegalArgumentException e) {
        return AjaxResult.error(400, e.getMessage());
    }
}
