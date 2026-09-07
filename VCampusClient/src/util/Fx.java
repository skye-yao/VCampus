package util;
public final class Fx {
    private Fx(){}
    public static void run(Runnable action){if(javafx.application.Platform.isFxApplicationThread())action.run();else javafx.application.Platform.runLater(action);}
}
