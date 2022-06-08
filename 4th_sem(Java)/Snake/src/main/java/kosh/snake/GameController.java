package kosh.snake;

import javafx.animation.AnimationTimer;
import javafx.application.Application;
import javafx.scene.control.Label;
import javafx.stage.Stage;

public class GameController {

    public void start(Stage primaryStage, int levelNum) {
        stage = primaryStage;
        engine = new Engine(new Coordinates(5,1), painter.getWidth(), painter.getHeight());
        engine.addSubscriber(painter);
        painter.drawInitialField(engine.getField());
        painter.start(stage);
        timer.start();
    }

    private void keyControl() {
        stage.getScene().setOnKeyPressed(event -> {
            System.out.println("event code: " + event.getCode());
            switch (event.getCode()) {
                case W, UP -> engine.getSnake().setDirection(Direction.UP);
                case A, LEFT -> engine.getSnake().setDirection(Direction.LEFT);
                case S, DOWN -> engine.getSnake().setDirection(Direction.DOWN);
                case D, RIGHT -> engine.getSnake().setDirection(Direction.RIGHT);
            }
        });
    }

    public void loadLevel(int levelNum) {
        engine = new Engine(new Coordinates(5,1), painter.getWidth(), painter.getHeight());
//        engine.loadField("/level" + levelNum + ".txt");

    }

    private final AnimationTimer timer = new AnimationTimer() {
        @Override
        public void handle(long now) {
            keyControl();
            if (now - lastActivated > timeout) {
                lastActivated = now;
                scoreLabel.setText(String.valueOf(engine.getScore()));
                if (!engine.makeStep()) {
                    timer.stop();
                    System.out.println("GAME OVER");
//                    //gameover
                }
            }
        }
    };

    public void setTimer(boolean run) {
        if (run) {
            timer.start();
        } else {
            timer.stop();
        }
    }


    private final GamePainter painter = new GamePainter();
    private Engine engine;
    private static Stage stage;
    private Label scoreLabel = new Label("Score: ");
    private long lastActivated = 0;
    private final int timeout = 200000000;
}
