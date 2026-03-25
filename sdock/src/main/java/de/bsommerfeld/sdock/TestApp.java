package de.bsommerfeld.sdock;

import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;

public class TestApp extends Application {

    @Override
    public void start(Stage primaryStage) {
        BorderPane root = new BorderPane();
        DockPanel dockPanel = new DockPanel();
        
        Button btnAddWidget = new Button("Add Widget");
        btnAddWidget.setOnAction(e -> {
            DockWidget dw = new DockWidget("Test Widget " + (dockPanel.getChildren().size()));
            Label l = new Label("Content Area for Widget " + (dockPanel.getChildren().size()));
            l.setStyle("-fx-text-fill: #dcddde;");
            dw.setCenter(l);
            dockPanel.addWindow(dw);
        });
        
        Button btnToggleLayout = new Button("Toggle Layout");
        btnToggleLayout.setOnAction(e -> {
            if (dockPanel.getLayout() == DockLayout.FILL) {
                dockPanel.setDockLayout(DockLayout.STACK);
            } else {
                dockPanel.setDockLayout(DockLayout.FILL);
            }
        });

        // Test variables for layout storage
        final String[] layoutState = new String[1];
        
        Button btnExport = new Button("Export Layout");
        btnExport.setOnAction(e -> {
            layoutState[0] = dockPanel.exportLayout();
            System.out.println("Exported layout json: " + layoutState[0]);
        });
        
        Button btnImport = new Button("Import Layout");
        btnImport.setOnAction(e -> {
            if (layoutState[0] != null) {
                dockPanel.importLayout(layoutState[0]);
            }
        });

        HBox toolbar = new HBox(10, btnAddWidget, btnToggleLayout, btnExport, btnImport);
        toolbar.setPadding(new Insets(10));
        toolbar.setStyle("-fx-background-color: #202225; -fx-border-color: #36393f; -fx-border-width: 0 0 1 0;");
        
        root.setTop(toolbar);
        root.setCenter(dockPanel);
        
        Scene scene = new Scene(root, 1000, 700);
        primaryStage.setTitle("sDock Test App");
        primaryStage.setScene(scene);
        primaryStage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
