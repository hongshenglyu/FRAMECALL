package com.footballref.assist.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.footballref.assist.domain.AnalysisTask;
import com.footballref.assist.domain.DisputeEvent;
import com.footballref.assist.domain.ReviewRecord;
import com.footballref.assist.domain.VideoAsset;
import com.footballref.assist.domain.enums.EventType;
import com.footballref.assist.domain.enums.ReviewResult;
import com.footballref.assist.domain.enums.RiskLevel;
import com.footballref.assist.repository.DisputeEventRepository;
import com.footballref.assist.repository.ReviewRecordRepository;
import com.lowagie.text.Chunk;
import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class ReportService {
    private static final ZoneId REPORT_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
            .withZone(REPORT_ZONE);

    private final DisputeEventRepository events;
    private final ReviewRecordRepository reviews;
    private final ObjectMapper objectMapper;
    private final MediaAccessService media;

    public ReportService(DisputeEventRepository events, ReviewRecordRepository reviews, ObjectMapper objectMapper,
                         MediaAccessService media) {
        this.events = events;
        this.reviews = reviews;
        this.objectMapper = objectMapper;
        this.media = media;
    }

    public byte[] generatePdf(AnalysisTask task, List<DisputeEvent> eventList, String selection) throws Exception {
        media.controlledPath(task.getOutputDir());
        ReportFonts fonts = createFonts();
        Document document = new Document(PageSize.A4, 36, 36, 42, 42);
        ByteArrayOutputStream output=new ByteArrayOutputStream();
        PdfWriter.getInstance(document, output);
        document.open();
        try {
        addTitle(document, fonts, task);
        document.add(new Paragraph("结果选择："+selection,fonts.small()));
        addOverview(document, fonts, task, eventList);
        addEventTable(document, fonts, eventList);
        addEventDetails(document, fonts, eventList);

        } finally { document.close(); }
        return output.toByteArray();
    }

    private void addTitle(Document document, ReportFonts fonts, AnalysisTask task) throws DocumentException {
        Paragraph title = new Paragraph("足球裁判辅助争议事件复核报告", fonts.title());
        title.setAlignment(Element.ALIGN_CENTER);
        title.setSpacingAfter(10);
        document.add(title);

        Paragraph subtitle = new Paragraph("报告生成时间：" + DATE_TIME.format(Instant.now())
                + "    任务编号：#" + task.getId(), fonts.small());
        subtitle.setAlignment(Element.ALIGN_CENTER);
        subtitle.setSpacingAfter(18);
        document.add(subtitle);
    }

    private void addOverview(Document document, ReportFonts fonts, AnalysisTask task, List<DisputeEvent> eventList)
            throws DocumentException {
        VideoAsset video = task.getVideo();
        long reviewedCount = eventList.stream().filter(event -> latestReview(event) != null).count();
        boolean contactOnly = !eventList.isEmpty() && eventList.stream().allMatch(event -> event.getEventType()==EventType.CONTACT);
        long confirmedCount = eventList.stream()
                .map(this::latestReview)
                .filter(review -> review != null && review.getResult() == (contactOnly?ReviewResult.VISIBLE_CONTACT:ReviewResult.CONFIRMED))
                .count();

        addSectionHeading(document, fonts, "一、任务概览");

        PdfPTable table = new PdfPTable(new float[]{1.2f, 2.4f, 1.2f, 2.4f});
        table.setWidthPercentage(100);
        addInfoCell(table, fonts, "视频标题", video.getTitle());
        addInfoCell(table, fonts, "赛事", video.getCompetition());
        addInfoCell(table, fonts, "对阵双方", video.getTeams());
        addInfoCell(table, fonts, "比赛时间", video.getMatchTime() == null ? "未填写" : video.getMatchTime().toString());
        addInfoCell(table, fonts, "任务状态", String.valueOf(task.getStatus()));
        addInfoCell(table, fonts, "灵敏度", String.valueOf(task.getSensitivity()));
        addInfoCell(table, fonts, "事件数量", String.valueOf(eventList.size()));
        addInfoCell(table, fonts, contactOnly?"已复核/可见接触":"已复核/成立", reviewedCount + " / " + confirmedCount);
        document.add(table);
        document.add(Chunk.NEWLINE);
    }

    private void addEventTable(Document document, ReportFonts fonts, List<DisputeEvent> eventList) throws DocumentException {
        addSectionHeading(document, fonts, "二、争议事件列表");

        PdfPTable table = new PdfPTable(new float[]{1.3f, 1.2f, 1.1f, 1.1f, 1.1f, 1.4f, 1.5f});
        table.setWidthPercentage(100);
        addHeaderCell(table, fonts, "事件ID");
        addHeaderCell(table, fonts, "类型");
        addHeaderCell(table, fonts, "风险");
        addHeaderCell(table, fonts, "时间");
        addHeaderCell(table, fonts, "优先级/置信度");
        addHeaderCell(table, fonts, "涉事ID");
        addHeaderCell(table, fonts, "复核结论");

        for (DisputeEvent event : eventList) {
            ReviewRecord latest = latestReview(event);
            addBodyCell(table, fonts, event.getExternalId());
            addBodyCell(table, fonts, label(event.getEventType()));
            addBodyCell(table, fonts, label(event.getRiskLevel()));
            addBodyCell(table, fonts, formatMs(event.getTimestampMs()));
            addBodyCell(table, fonts, String.format(Locale.US, "%.3f", event.getConfidence()));
            addBodyCell(table, fonts, event.getInvolvedTrackIds());
            addBodyCell(table, fonts, latest == null ? "待复核" : label(latest.getResult()));
        }
        document.add(table);
        document.add(Chunk.NEWLINE);
    }

    private void addEventDetails(Document document, ReportFonts fonts, List<DisputeEvent> eventList) throws DocumentException {
        addSectionHeading(document, fonts, "三、事件复核详情");

        for (DisputeEvent event : eventList) {
            ReviewRecord latest = latestReview(event);
            Paragraph heading = new Paragraph(event.getExternalId() + " ["+(event.getAttemptId()==null?"LEGACY":event.getAttemptId())+"]  "
                    + label(event.getEventType()) + " / " + label(event.getRiskLevel())
                    + " / " + formatMs(event.getTimestampMs()), fonts.heading());
            heading.setSpacingBefore(6);
            heading.setSpacingAfter(5);
            document.add(heading);

            document.add(new Paragraph("事件摘要：" + safe(event.getSummary()), fonts.body()));
            if (event.getEventType()==EventType.CONTACT) document.add(new Paragraph("优先级仅用于复核排序，不是犯规概率；峰值不是首次可见接触。",fonts.body()));
            document.add(new Paragraph("涉事球员/轨迹ID：" + safe(event.getInvolvedTrackIds()), fonts.body()));
            addKeyframePreview(document, fonts, event);
            addEvidenceGroups(document, fonts, event);
            addTrackBoxes(document, fonts, event);
            if (latest == null) {
                document.add(new Paragraph("裁判复核：待复核", fonts.body()));
            } else {
                document.add(new Paragraph("裁判复核：" + label(latest.getResult())
                        + "；原因：" + safe(latest.getReasonCode())
                        + "；复核人：" + latest.getReviewer().getDisplayName()
                        + "；版本：" + latest.getRevision(), fonts.body()));
                if (latest.getNote() != null && !latest.getNote().isBlank()) {
                    document.add(new Paragraph("复核备注：" + latest.getNote(), fonts.body()));
                }
            }
            document.add(new Paragraph("媒体引用：事件 #" + event.getId() + "（需登录后查看）", fonts.small()));
            document.add(Chunk.NEWLINE);
        }
    }

    private void addKeyframePreview(Document document, ReportFonts fonts, DisputeEvent event) throws DocumentException {
        try {
            Path keyframe = media.eventFile(event, "keyframe");
            Image image = Image.getInstance(keyframe.toString());
            image.scaleToFit(260, 148);
            image.setSpacingBefore(6);
            image.setSpacingAfter(6);
            document.add(image);
        } catch (Exception ignored) {
            document.add(new Paragraph("关键帧预览：媒体不可用；原审计记录已保留。", fonts.small()));
        }
    }

    private void addEvidenceGroups(Document document, ReportFonts fonts, DisputeEvent event) throws DocumentException {
        List<JsonNode> metrics = parseArray(event.getEvidenceJson());
        if (metrics.isEmpty()) {
            document.add(new Paragraph("证据指标：无", fonts.body()));
            return;
        }

        addMetricGroup(document, fonts, "轨迹规则证据", metrics, Set.of(
                "min_distance", "proximity", "relative_velocity_delta", "closing_speed", "contact_window"
        ));
        addMetricGroup(document, fonts, "局部姿态证据", metrics, Set.of(
                "pose_anomaly", "box_overlap_ratio", "limb_extension", "body_axis_delta"
        ));
        addOtherMetricGroup(document, fonts, metrics);
    }

    private void addMetricGroup(Document document, ReportFonts fonts, String title, List<JsonNode> metrics, Set<String> names)
            throws DocumentException {
        List<JsonNode> selected = metrics.stream()
                .filter(metric -> names.contains(metric.path("name").asText()))
                .toList();
        addMetricTable(document, fonts, title, selected);
    }

    private void addOtherMetricGroup(Document document, ReportFonts fonts, List<JsonNode> metrics) throws DocumentException {
        Set<String> grouped = Set.of(
                "min_distance", "proximity", "relative_velocity_delta", "closing_speed", "contact_window",
                "pose_anomaly", "box_overlap_ratio", "limb_extension", "body_axis_delta"
        );
        List<JsonNode> selected = metrics.stream()
                .filter(metric -> !grouped.contains(metric.path("name").asText()))
                .toList();
        addMetricTable(document, fonts, "其他候选线索", selected);
    }

    private void addMetricTable(Document document, ReportFonts fonts, String title, List<JsonNode> metrics)
            throws DocumentException {
        if (metrics.isEmpty()) {
            return;
        }

        Paragraph heading = new Paragraph(title, fonts.heading());
        heading.setSpacingBefore(6);
        heading.setSpacingAfter(4);
        document.add(heading);

        PdfPTable table = new PdfPTable(new float[]{1.2f, 1.1f, 2.7f});
        table.setWidthPercentage(100);
        addHeaderCell(table, fonts, "指标");
        addHeaderCell(table, fonts, "数值");
        addHeaderCell(table, fonts, "说明");
        for (JsonNode metric : metrics) {
            String name = metric.path("name").asText("-");
            addBodyCell(table, fonts, metricLabel(name));
            addBodyCell(table, fonts, metricValue(metric));
            addBodyCell(table, fonts, metricDescription(metric));
        }
        document.add(table);
    }

    private void addTrackBoxes(Document document, ReportFonts fonts, DisputeEvent event) throws DocumentException {
        List<JsonNode> boxes = parseArray(event.getTrackBoxesJson());
        if (boxes.isEmpty()) {
            return;
        }

        Paragraph heading = new Paragraph("轨迹框", fonts.heading());
        heading.setSpacingBefore(6);
        heading.setSpacingAfter(4);
        document.add(heading);

        PdfPTable table = new PdfPTable(new float[]{0.8f, 1.2f, 2.4f, 1.0f});
        table.setWidthPercentage(100);
        addHeaderCell(table, fonts, "ID");
        addHeaderCell(table, fonts, "标签/队伍");
        addHeaderCell(table, fonts, "坐标 x1,y1,x2,y2");
        addHeaderCell(table, fonts, "置信度");
        for (JsonNode box : boxes) {
            addBodyCell(table, fonts, box.path("trackId").asText("-"));
            addBodyCell(table, fonts, boxLabel(box));
            addBodyCell(table, fonts, boxCoordinates(box.path("box")));
            addBodyCell(table, fonts, decimal(box.path("confidence").asDouble(0), 3));
        }
        document.add(table);
    }

    private ReviewRecord latestReview(DisputeEvent event) {
        List<ReviewRecord> reviewList = reviews.findByEventOrderByRevisionDesc(event);
        return reviewList.isEmpty() ? null : reviewList.get(0);
    }

    private void addSectionHeading(Document document, ReportFonts fonts, String text) throws DocumentException {
        Paragraph paragraph = new Paragraph(text, fonts.section());
        paragraph.setSpacingBefore(8);
        paragraph.setSpacingAfter(8);
        document.add(paragraph);
    }

    private void addInfoCell(PdfPTable table, ReportFonts fonts, String label, String value) {
        table.addCell(cell(label, fonts.tableHeader(), new Color(235, 240, 246), Element.ALIGN_LEFT));
        table.addCell(cell(safe(value), fonts.tableBody(), Color.WHITE, Element.ALIGN_LEFT));
    }

    private void addHeaderCell(PdfPTable table, ReportFonts fonts, String text) {
        table.addCell(cell(text, fonts.tableHeader(), new Color(31, 41, 55), Element.ALIGN_CENTER, Color.WHITE));
    }

    private void addBodyCell(PdfPTable table, ReportFonts fonts, String text) {
        table.addCell(cell(safe(text), fonts.tableBody(), Color.WHITE, Element.ALIGN_CENTER));
    }

    private PdfPCell cell(String text, Font font, Color background, int alignment) {
        return cell(text, font, background, alignment, Color.BLACK);
    }

    private PdfPCell cell(String text, Font font, Color background, int alignment, Color textColor) {
        Font coloredFont = new Font(font.getBaseFont(), font.getSize(), font.getStyle(), textColor);
        PdfPCell cell = new PdfPCell(new Phrase(text, coloredFont));
        cell.setPadding(6);
        cell.setHorizontalAlignment(alignment);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        cell.setBackgroundColor(background);
        cell.setBorderColor(new Color(210, 217, 226));
        cell.setBorder(Rectangle.BOX);
        return cell;
    }

    private List<JsonNode> parseArray(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            if (!node.isArray()) {
                return List.of();
            }
            List<JsonNode> values = new ArrayList<>();
            node.forEach(values::add);
            return values;
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private static String metricValue(JsonNode metric) {
        if (metric.path("value").isNull() || metric.path("value").isMissingNode()) return "不可计算（运动样本或质量证据不足）";
        double value = metric.path("value").asDouble(Double.NaN);
        String formatted = Double.isNaN(value) ? metric.path("value").asText("-") : decimal(value, 3);
        String unit = metric.path("unit").asText("");
        return unit.isBlank() ? formatted : formatted + " " + unit;
    }

    private static String metricLabel(String name) {
        return switch (name) {
            case "min_distance" -> "最小距离";
            case "proximity" -> "接近程度";
            case "relative_velocity_delta" -> "相对速度突变";
            case "closing_speed" -> "闭合速度";
            case "contact_window" -> "接触窗口";
            case "pose_anomaly" -> "姿态异常";
            case "box_overlap_ratio" -> "局部重叠";
            case "limb_extension" -> "肢体伸展";
            case "body_axis_delta" -> "身体轴线差";
            case "ball_upper_limb_distance" -> "球到上肢距离";
            case "ball_direction_change" -> "球路变化";
            case "line_margin" -> "越位线边距";
            case "calibration_confidence" -> "标定可信度";
            default -> name;
        };
    }

    private static String metricDescription(JsonNode metric) {
        String name = metric.path("name").asText("");
        String mapped = switch (name) {
            case "min_distance" -> "候选球员对中心点最小距离";
            case "proximity" -> "由轨迹距离压缩得到的接近程度";
            case "relative_velocity_delta" -> "接触窗口附近两名球员相对速度变化";
            case "closing_speed" -> "候选帧前两名球员互相靠近速度";
            case "contact_window" -> "接近程度与闭合速度的综合窗口评分";
            case "pose_anomaly" -> "只针对候选球员对的局部姿态异常代理分";
            case "box_overlap_ratio" -> "两名球员局部框的重叠比例";
            case "limb_extension" -> "肢体伸展/接触姿态代理分";
            case "body_axis_delta" -> "局部框内身体轴线差异";
            case "ball_upper_limb_distance" -> "球与上肢区域的候选距离";
            case "ball_direction_change" -> "球路方向变化角度";
            case "line_margin" -> "进攻球员相对越位线的边距";
            case "calibration_confidence" -> "场地标定/透视变换可信度";
            default -> "";
        };
        return mapped.isBlank() ? safe(metric.path("description").asText("")) : mapped;
    }

    private static String boxLabel(JsonNode box) {
        String label = box.path("label").asText("-");
        if ("player".equals(label)) {
            label = "球员";
        }
        String team = box.path("team").asText("");
        return team.isBlank() ? label : label + " / " + team;
    }

    private static String boxCoordinates(JsonNode coordinates) {
        if (!coordinates.isArray() || coordinates.size() < 4) {
            return "-";
        }
        List<String> values = new ArrayList<>();
        coordinates.forEach(value -> values.add(String.valueOf(Math.round(value.asDouble()))));
        return String.join(", ", values);
    }

    private static String decimal(double value, int scale) {
        return String.format(Locale.US, "%." + scale + "f", value);
    }

    private String evidenceSummary(String evidenceJson) {
        if (evidenceJson == null || evidenceJson.isBlank()) {
            return "无";
        }
        try {
            JsonNode node = objectMapper.readTree(evidenceJson);
            if (!node.isArray() || node.isEmpty()) {
                return "无";
            }
            StringBuilder builder = new StringBuilder();
            for (JsonNode metric : node) {
                if (!builder.isEmpty()) {
                    builder.append("；");
                }
                builder.append(metric.path("name").asText("metric"))
                        .append("=")
                        .append(metric.path("value").asText("-"));
                String unit = metric.path("unit").asText("");
                if (!unit.isBlank()) {
                    builder.append(unit);
                }
            }
            return builder.toString();
        } catch (Exception ignored) {
            return truncate(evidenceJson, 240);
        }
    }

    private static String formatMs(long ms) {
        return String.format(Locale.US, "%.2fs", ms / 1000.0);
    }

    private static String label(EventType type) {
        return switch (type) {
            case FOUL -> "犯规";
            case HANDBALL -> "手球";
            case OFFSIDE -> "越位";
            case CONTACT -> "身体接触候选（非犯规判定）";
        };
    }

    private static String label(RiskLevel risk) {
        return switch (risk) {
            case HIGH -> "高";
            case MEDIUM -> "中";
            case LOW -> "低";
            case UNCERTAIN -> "不确定";
        };
    }

    private static String label(ReviewResult result) {
        return switch (result) {
            case CONFIRMED -> "成立";
            case REJECTED -> "不成立";
            case UNCERTAIN -> "无法判断";
            case VISIBLE_CONTACT -> "可见身体接触（非犯规判定）";
            case NO_VISIBLE_CONTACT -> "未见身体接触";
            case INSUFFICIENT_VIEW -> "画面不足以判断";
        };
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return safe(value);
        }
        return value.substring(0, maxLength) + "...";
    }

    private ReportFonts createFonts() throws Exception {
        BaseFont base = createChineseBaseFont();
        return new ReportFonts(
                new Font(base, 18, Font.BOLD),
                new Font(base, 13, Font.BOLD),
                new Font(base, 11, Font.BOLD),
                new Font(base, 10, Font.NORMAL),
                new Font(base, 9, Font.NORMAL),
                new Font(base, 9, Font.BOLD),
                new Font(base, 8, Font.NORMAL)
        );
    }

    private BaseFont createChineseBaseFont() throws Exception {
        String[] candidates = {
                "C:/Windows/Fonts/msyh.ttc,0",
                "C:/Windows/Fonts/simsun.ttc,0",
                "C:/Windows/Fonts/simhei.ttf",
                "STSong-Light"
        };
        for (String candidate : candidates) {
            try {
                if ("STSong-Light".equals(candidate)) {
                    return BaseFont.createFont(candidate, "UniGB-UCS2-H", BaseFont.NOT_EMBEDDED);
                }
                return BaseFont.createFont(candidate, BaseFont.IDENTITY_H, BaseFont.EMBEDDED);
            } catch (Exception ignored) {
                // Try the next font candidate.
            }
        }
        return BaseFont.createFont(BaseFont.HELVETICA, BaseFont.WINANSI, BaseFont.NOT_EMBEDDED);
    }

    private record ReportFonts(
            Font title,
            Font section,
            Font heading,
            Font body,
            Font small,
            Font tableHeader,
            Font tableBody
    ) {
    }
}
