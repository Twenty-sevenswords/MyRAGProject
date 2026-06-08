package com.yizhaoqi.smartpai.mcp.router.impl;

import com.yizhaoqi.smartpai.config.properties.RouterProperties;
import com.yizhaoqi.smartpai.mcp.context.McpContext;
import com.yizhaoqi.smartpai.mcp.router.PreRouter;
import com.yizhaoqi.smartpai.mcp.router.RouteResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 日期时间路由器
 * 处理日期、时间相关的问题，直接返回答案，无需 RAG
 *
 * 支持的问题类型：
 * - "今天是周几"
 * - "今天星期几"
 * - "现在几点了"
 * - "今天是几月几号"
 * - "今年是哪一年"
 * - "距离春节还有多少天"
 */
@Component
public class DateTimeRouter implements PreRouter {

    @Autowired
    private RouterProperties routerProperties;

    // 匹配模式列表
    private static final List<Pattern> PATTERNS = Arrays.asList(
            // 星期/周几相关
            Pattern.compile("(今天|今儿|现在).*(周几|星期几|礼拜几)"),
            Pattern.compile("(今天|今儿).*(周|星期|礼拜)[一二三四五六日天]"),
            Pattern.compile(".*(是|是第).*(周|星期|礼拜)"),
            
            // 日期相关
            Pattern.compile("(今天|今儿).*(几号|几日|日期)"),
            Pattern.compile("(今天|今儿).*(几月|什么月份)"),
            Pattern.compile("(今年|今年).*(哪年|什么年|几年)"),
            Pattern.compile("(今天|今儿).*(什么日期|什么日子)"),
            
            // 时间相关
            Pattern.compile("(现在|此时).*(几点|什么时间)"),
            Pattern.compile("^(几点了|现在几点)[？?]?"),
            
            // 倒计时相关
            Pattern.compile("距离?(春节|元旦|国庆|中秋|端午).*(还有|还有多少).*(天|日)"),
            Pattern.compile("(春节|元旦|国庆|中秋|端午).*(还有|还有多少).*(天|日)")
    );

    @Override
    public String getName() {
        return "DateTimeRouter";
    }

    @Override
    public String getDescription() {
        return "处理日期时间相关问题，如'今天是周几'、'现在几点'等";
    }

    @Override
    public int getOrder() {
        return 10;  // 高优先级
    }

    @Override
    public boolean isEnabled() {
        return routerProperties.isRouterEnabled(getName());
    }

    @Override
    public boolean matches(McpContext context) {
        String message = context.getUserMessage().toLowerCase().trim();
        
        for (Pattern pattern : PATTERNS) {
            if (pattern.matcher(message).find()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public RouteResult route(McpContext context) {
        String message = context.getUserMessage().toLowerCase().trim();
        String answer = generateDateTimeAnswer(message);
        
        return RouteResult.directAnswer(answer, "日期时间问题，直接计算返回");
    }

    /**
     * 根据问题生成日期时间答案
     */
    private String generateDateTimeAnswer(String question) {
        LocalDateTime now = LocalDateTime.now();
        LocalDate today = LocalDate.now();
        
        // 星期几
        if (question.contains("周几") || question.contains("星期几") || question.contains("礼拜几")) {
            DayOfWeek dayOfWeek = today.getDayOfWeek();
            String[] weekDays = {"一", "二", "三", "四", "五", "六", "日"};
            return String.format("今天是星期%s（%s）", 
                    weekDays[dayOfWeek.getValue() - 1],
                    today.format(DateTimeFormatter.ofPattern("yyyy年MM月dd日")));
        }
        
        // 几号/日期
        if (question.contains("几号") || question.contains("几日") || question.contains("日期") || question.contains("什么日子")) {
            return String.format("今天是%s，农历暂不支持查询", 
                    today.format(DateTimeFormatter.ofPattern("yyyy年MM月dd日")));
        }
        
        // 几月
        if (question.contains("几月") || question.contains("什么月份")) {
            return String.format("现在是%d月", today.getMonthValue());
        }
        
        // 哪年
        if (question.contains("哪年") || question.contains("什么年") || question.contains("几年")) {
            int year = today.getYear();
            String[] gan = {"甲", "乙", "丙", "丁", "戊", "己", "庚", "辛", "壬", "癸"};
            String[] zhi = {"子", "丑", "寅", "卯", "辰", "巳", "午", "未", "申", "酉", "戌", "亥"};
            int ganIndex = (year - 4) % 10;
            int zhiIndex = (year - 4) % 12;
            return String.format("今年是%d年，%s%s年", year, gan[ganIndex], zhi[zhiIndex]);
        }
        
        // 几点
        if (question.contains("几点") || question.contains("什么时间")) {
            return String.format("现在是%s", now.format(DateTimeFormatter.ofPattern("HH时mm分")));
        }
        
        // 节日倒计时
        if (question.contains("春节") || question.contains("元旦") || question.contains("国庆") 
                || question.contains("中秋") || question.contains("端午")) {
            return calculateCountdown(question, today);
        }
        
        // 默认返回完整日期时间
        return String.format("现在是%s，星期%s", 
                now.format(DateTimeFormatter.ofPattern("yyyy年MM月dd日 HH时mm分")),
                new String[]{"一", "二", "三", "四", "五", "六", "日"}[today.getDayOfWeek().getValue() - 1]);
    }

    /**
     * 计算节日倒计时
     */
    private String calculateCountdown(String question, LocalDate today) {
        int year = today.getYear();
        LocalDate targetDate = null;
        String festivalName = "";
        
        if (question.contains("元旦")) {
            festivalName = "元旦";
            targetDate = LocalDate.of(year + 1, 1, 1);
        } else if (question.contains("春节")) {
            festivalName = "春节";
            // 简化处理：春节日期需要查农历，这里用近似日期
            targetDate = LocalDate.of(year + 1, 2, 1); // 近似
        } else if (question.contains("国庆")) {
            festivalName = "国庆节";
            targetDate = LocalDate.of(year, 10, 1);
            if (targetDate.isBefore(today)) {
                targetDate = LocalDate.of(year + 1, 10, 1);
            }
        } else if (question.contains("中秋")) {
            festivalName = "中秋节";
            // 简化处理：中秋日期需要查农历
            targetDate = LocalDate.of(year, 9, 15); // 近似
            if (targetDate.isBefore(today)) {
                targetDate = LocalDate.of(year + 1, 9, 15);
            }
        } else if (question.contains("端午")) {
            festivalName = "端午节";
            // 简化处理
            targetDate = LocalDate.of(year, 6, 1); // 近似
            if (targetDate.isBefore(today)) {
                targetDate = LocalDate.of(year + 1, 6, 1);
            }
        }
        
        if (targetDate != null) {
            long days = ChronoUnit.DAYS.between(today, targetDate);
            if (days < 0) {
                return String.format("%s已经过去了%d天", festivalName, Math.abs(days));
            } else if (days == 0) {
                return String.format("今天是%s！", festivalName);
            } else {
                return String.format("距离%s还有%d天（%s）", 
                        festivalName, days, 
                        targetDate.format(DateTimeFormatter.ofPattern("yyyy年MM月dd日")));
            }
        }
        
        return "抱歉，无法计算该节日的日期";
    }
}
