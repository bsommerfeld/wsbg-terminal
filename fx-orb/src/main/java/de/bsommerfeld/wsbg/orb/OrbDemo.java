package de.bsommerfeld.wsbg.orb;

import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.stage.Stage;

/** Try-out window: the orb in autumn. Hover it. */
public final class OrbDemo extends Application {

    static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        MarbleOrb orb = new MarbleOrb();
        orb.setSize(200);
        orb.setPalette(OrbPalette.HERBST);

        StackPane root = new StackPane(orb);
        root.setPadding(new Insets(40));
        stage.setScene(new Scene(root, Color.web("#323130")));
        stage.show();
    }
}
