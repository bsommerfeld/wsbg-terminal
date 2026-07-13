package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.db.DeepDiveRecord.ChartFigure;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Dev-only dump: writes the sample figures to an HTML page for eyeballing. */
@Tag("integration")
class DeepDiveChartsPreviewDump {

    @Test
    void dump() throws Exception {
        String out = System.getenv("DD_CHART_PREVIEW");
        if (out == null) return;
        var t = new DeepDiveChartsTest();
        List<ChartFigure> figures = new DeepDiveCharts("de").build(
                DeepDiveChartsTest.snapshot(), DeepDiveChartsTest.deepDive(),
                DeepDiveChartsTest.analystView(), DeepDiveChartsTest.shorts(),
                DeepDiveChartsTest.insider());
        StringBuilder html = new StringBuilder("<!doctype html><meta charset=utf-8><body style='background:#fff;max-width:620px;margin:20px auto;font-family:sans-serif'>");
        for (ChartFigure f : figures) {
            html.append("<h4 style='font-size:12px;color:#52514e'>").append(f.title())
                .append(" <small style='color:#898781;float:right'>").append(f.note()).append("</small></h4>")
                .append(f.svg());
        }
        Files.writeString(Path.of(out), html.append("</body>").toString());
    }
}
