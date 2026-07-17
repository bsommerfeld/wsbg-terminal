package de.bsommerfeld.wsbg.terminal.agent.event;

import de.bsommerfeld.wsbg.terminal.db.DeepDiveRecord;

import java.util.List;

/**
 * The run's figure layer the moment it is built (before any prose stands) —
 * the live view slots the figures into the report mirror as they will print.
 * The archived record stays the single source of truth for the final report.
 */
public record DeepDiveLiveChartsEvent(String subject,
        List<DeepDiveRecord.ChartFigure> charts) {
}
