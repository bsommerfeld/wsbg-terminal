package de.bsommerfeld.sdock;

import java.util.List;

public class DockLayoutState {
    public String layoutMode;
    public List<WidgetState> windows;

    public static class WidgetState {
        public String title;
        public double x;
        public double y;
        public double width;
        public double height;
    }
}
