/*
 * Copyright (C) 2020 Emerson Pinter - All Rights Reserved
 */

/*    This file is part of TQ Respec.

    TQ Respec is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    TQ Respec is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with TQ Respec.  If not, see <http://www.gnu.org/licenses/>.
*/

package br.com.pinter.tqrespec;

import br.com.pinter.tqdatabase.Database;
import br.com.pinter.tqrespec.core.*;
import br.com.pinter.tqrespec.gui.IconHelper;
import br.com.pinter.tqrespec.gui.MainController;
import br.com.pinter.tqrespec.gui.ResizeListener;
import br.com.pinter.tqrespec.logging.Log;
import br.com.pinter.tqrespec.tqdata.Db;
import br.com.pinter.tqrespec.tqdata.GameInfo;
import br.com.pinter.tqrespec.tqdata.Txt;
import br.com.pinter.tqrespec.util.Constants;
import br.com.pinter.tqrespec.util.Util;
import com.google.inject.Inject;
import javafx.application.Application;
import javafx.application.HostServices;
import javafx.application.Platform;
import javafx.application.Preloader;
import javafx.beans.binding.Bindings;
import javafx.concurrent.Task;
import javafx.event.Event;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.TextArea;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.text.Font;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.WindowEvent;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.SystemUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;
import java.util.logging.Level;

public class Main extends Application {
    @Inject
    private Db db;

    @Inject
    private Txt txt;

    @Inject
    private FXMLLoader fxmlLoader;

    @Inject
    private GameInfo gameInfo;

    private System.Logger logger;

    public static void main(String... args) {
        System.setProperty("javafx.preloader", "br.com.pinter.tqrespec.gui.AppPreloader");
        Log.setupGlobalLogging();

        launch(args);
    }

    public void prepareInjectionContext() {
        //prepare injector instance
        List<com.google.inject.Module> modules = new ArrayList<>();
        GuiceModule hostServicesBinding = new GuiceModule() {
            @Override
            protected void configure() {
                bind(HostServices.class).toInstance(getHostServices());
            }
        };
        modules.add(new GuiceModule());
        modules.add(hostServicesBinding);
        InjectionContext injectionContext = new InjectionContext(this, modules);
        injectionContext.initialize();
    }


    private void load(Stage primaryStage) {
        logger.log(System.Logger.Level.DEBUG, "preloading data");
        File selectedDirectory = null;
        try {
            gameInfo.getDatabasePath();
            gameInfo.getTextPath();
            notifyPreloader(new Preloader.ProgressNotification(0.2));
        } catch (FileNotFoundException e) {
            Util.showError(Util.getUIMessage("main.gameNotDetected"), Util.getUIMessage("main.chooseGameDirectory"));
            logger.log(System.Logger.Level.ERROR, "game path not detected, showing DirectoryChooser",e);
            DirectoryChooser directoryChooser = new DirectoryChooser();
            directoryChooser.setTitle(Util.getUIMessage("main.chooseGameDirectory"));
            selectedDirectory = directoryChooser.showDialog(primaryStage);
            if (selectedDirectory == null) {
                System.exit(1);
            }
        }
        if (selectedDirectory != null) {
            try {
                gameInfo.setGamePath(selectedDirectory.getPath());
            } catch (GameNotFoundException e) {
                logger.log(System.Logger.Level.ERROR,"Error", e);
                Util.showError(Util.getUIMessage("main.gameNotDetected"),null);
                Util.closeApplication();
            }
        }

        Task<Void> task = new Task<>() {
            @Override
            public Void call() {
                //preload game database metadata and skills
                notifyPreloader(new Preloader.ProgressNotification(0.3));
                db.initialize();
                notifyPreloader(new Preloader.ProgressNotification(0.7));
                db.skills().preload();
                //preload text
                notifyPreloader(new Preloader.ProgressNotification(0.9));
                db.player().preload();
                txt.preload();

                try {
                    new Thread(new GameProcessMonitor(gameInfo.getGamePath())).start();
                } catch (GameNotFoundException e) {
                    logger.log(System.Logger.Level.ERROR, Constants.ERROR_MSG_EXCEPTION, e);
                }

                return null;
            }
        };
        task.setOnFailed(e -> {
            gameInfo.removeSavedDetectedGamePath();

            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setHeaderText("Error loading application");
            logger.log(System.Logger.Level.ERROR, "Error loading application", e.getSource().getException());
            alert.initOwner(primaryStage);
            alert.setTitle(Util.getBuildTitle());
            TextArea textArea = new TextArea(ExceptionUtils.getStackTrace(e.getSource().getException()));
            textArea.setMaxWidth(Double.MAX_VALUE);
            textArea.setMaxHeight(Double.MAX_VALUE);
            alert.getDialogPane().setExpandableContent(textArea);
            alert.showAndWait();
            System.exit(1);
        });

        task.setOnSucceeded(e -> {
            notifyPreloader(new Preloader.ProgressNotification(1.0));
            primaryStage.show();
        });
        new Thread(task).start();
        primaryStage.setOnShown(windowEvent -> notifyPreloader(new Preloader.StateChangeNotification(
                Preloader.StateChangeNotification.Type.BEFORE_START)));

    }

    @Override
    public void start(Stage primaryStage) {
        try {
            new SingleInstanceLock().lock();
        } catch (IOException e) {
            Util.showError("TQRespec is already running", null);
            Platform.exit();
            System.exit(0);
        }


        parseCliParams();

        prepareInjectionContext();

        logger = Log.getLogger(Main.class.getName());
        logger.log(System.Logger.Level.DEBUG, State.get().getDebugPrefix());
        notifyPreloader(new Preloader.ProgressNotification(0.1));
        prepareMainStage(primaryStage);
        load(primaryStage);
    }

    public void prepareMainStage(Stage primaryStage) {
        logger.log(System.Logger.Level.DEBUG, "starting application");
        Font.loadFont(getClass().getResourceAsStream("/fxml/albertus-mt.ttf"), 16);
        Font.loadFont(getClass().getResourceAsStream("/fxml/albertus-mt-light.ttf"), 16);
        Font.loadFont(getClass().getResourceAsStream("/fxml/fa5-free-solid-900.ttf"), 16);

        Thread.setDefaultUncaughtExceptionHandler(ExceptionHandler::unhandled);

        Parent root;
        try {
            State.get().setLocale(gameInfo.getGameLanguage());
            fxmlLoader.setResources(ResourceBundle.getBundle("i18n.UI", State.get().getLocale()));
            fxmlLoader.setLocation(getClass().getResource(Constants.UI.MAIN_FXML));
            root = fxmlLoader.load();
        } catch (IOException e) {
            logger.log(System.Logger.Level.ERROR, Constants.ERROR_MSG_EXCEPTION, e);
            return;
        }

        primaryStage.setTitle(Util.getBuildTitle());
        primaryStage.getIcons().addAll(IconHelper.getAppIcons());
        Scene scene = new Scene(root);
        primaryStage.setScene(scene);

        //disable alt+f4
        Platform.setImplicitExit(false);
        primaryStage.setOnCloseRequest(Event::consume);

        //remove default window decoration
        if (SystemUtils.IS_OS_WINDOWS) {
            primaryStage.initStyle(StageStyle.TRANSPARENT);
        } else {
            primaryStage.initStyle(StageStyle.UNDECORATED);
        }

        //disable maximize
        primaryStage.resizableProperty().setValue(Boolean.FALSE);

        ResizeListener listener = new ResizeListener(primaryStage);
        scene.setOnMouseMoved(listener);
        scene.setOnMousePressed(listener);
        scene.setOnMouseDragged(listener);

        primaryStage.getScene().getRoot().styleProperty().bind(Bindings.format("-fx-font-size: %sem;", Constants.INITIAL_FONT_SIZE));

        // min* and max* set to -1 will force javafx to use values defined on root element
        primaryStage.setMinHeight(root.minHeight(-1));
        primaryStage.setMinWidth(root.minWidth(-1));
        primaryStage.setMaxHeight(root.maxHeight(-1));
        primaryStage.setMaxWidth(root.maxWidth(-1));

        primaryStage.addEventHandler(KeyEvent.KEY_PRESSED, (event -> {
            if (event.getCode() == KeyCode.ESCAPE) {
                Util.closeApplication();
            }
        }));

        //handler to prepare controls on startup, the use of initialize and risk of crash
        primaryStage.addEventHandler(WindowEvent.WINDOW_SHOWN, window -> MainController.mainFormInitialized.setValue(true));
    }

    private void parseCliParams() {
        String debugParam = getParameters().getNamed().get("debug");
        int debug = 0;
        try {
            if (StringUtils.isNotBlank(debugParam)) {
                debug = Integer.valueOf(debugParam);
            }
        } catch (NumberFormatException ignored) {
            //ignored
            return;
        }

        switch (debug) {
            case 9:
                State.get().addDebugPrefix("*", Level.FINER);
                break;
            case 8:
            case 7:
            case 6:
            case 5:
                State.get().addDebugPrefix("*", Level.FINE);
                State.get().addDebugPrefix(Database.class.getPackageName(), Level.FINER);
                State.get().addDebugPrefix(Main.class.getPackageName(), Level.FINER);
                break;
            case 4:
                State.get().addDebugPrefix("*", Level.FINE);
                State.get().addDebugPrefix(Main.class.getPackageName(), Level.FINER);
                break;
            case 3:
                State.get().addDebugPrefix(Main.class.getPackageName(), Level.FINER);
                State.get().addDebugPrefix(Database.class.getPackageName(), Level.FINE);
                break;
            case 2:
                State.get().addDebugPrefix(Main.class.getPackageName(), Level.FINE);
                State.get().addDebugPrefix(Database.class.getPackageName(), Level.FINE);
                break;
            case 1:
                State.get().addDebugPrefix(Main.class.getPackageName(), Level.FINE);
                break;
            case 0:
            default:
                //0 debug disabled
        }

    }
}
