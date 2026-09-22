package de.bsommerfeld.wsbg.terminal.i18n;

import javafx.beans.binding.Bindings;
import javafx.beans.binding.StringBinding;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;

import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

/**
 * Every string a person reads on screen comes through here. The bundle follows
 * {@link #localeProperty()}, which starts on the JVM default ({@code user.language});
 * nodes bound via {@link #bind(String)} re-translate live when it changes. A
 * missing key falls back to the English base bundle, and a key missing there
 * too shows the key itself - never a crash.
 */
public final class I18n {

    private static final String BUNDLE = "de.bsommerfeld.wsbg.terminal.i18n.messages";
    private static final ObjectProperty<Locale> LOCALE = new SimpleObjectProperty<>(Locale.getDefault());
    private static ResourceBundle bundle = ResourceBundle.getBundle(BUNDLE, LOCALE.get());

    static {
        LOCALE.addListener((o, was, now) -> bundle = ResourceBundle.getBundle(BUNDLE, now));
    }

    private I18n() {
    }

    public static ObjectProperty<Locale> localeProperty() {
        return LOCALE;
    }

    public static String get(String key) {
        try {
            return bundle.getString(key);
        } catch (MissingResourceException e) {
            return key;
        }
    }

    /** A binding that re-translates {@code key} whenever the locale changes. */
    public static StringBinding bind(String key) {
        return Bindings.createStringBinding(() -> get(key), LOCALE);
    }
}
