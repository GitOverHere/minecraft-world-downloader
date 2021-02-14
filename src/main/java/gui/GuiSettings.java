package gui;


import config.Config;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import proxy.auth.AuthStatus;
import proxy.auth.ClientAuthenticator;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.Arrays;
import java.util.List;

public class GuiSettings {
    public TextField server;
    public TextField portRemote;
    public TextField portLocal;

    public TextField worldOutputDir;
    public TextField centerX;
    public TextField centerZ;
    public TextField levelSeed;

    public TextField extendedDistance;
    public CheckBox measureRenderDistance;
    public CheckBox markUnsaved;
    public TextField overviewZoom;

    public Button saveButton;
    public Tab errTab;
    public TabPane tabPane;
    public TextArea errOutput;
    public TextField minecraftDir;
    public Label authDetailsVerifyLabel;
    public Tooltip authTooltip;
    public CheckBox disableWorldGen;
    public TextField minecraftUsername;
    public TextField accessToken;
    public Hyperlink authHelpLink;
    public Label portVerifyLabel;
    Config config;
    private boolean portInUse;

    public GuiSettings() {
        this.config = GuiManager.getConfig();
        GuiManager.registerSettingController(this);
    }

    @FXML
    void initialize() {
        if (config.isStarted()) {
            saveButton.setText("Save");
        }

        // connection tab
        server.setText(config.server);
        portRemote.setText("" + config.portRemote);
        portLocal.setText("" + config.portLocal);
        minecraftDir.setText(Config.getMinecraftPath());

        // output tab
        worldOutputDir.setText(config.worldOutputDir);
        centerX.setText("" + config.centerX);
        centerZ.setText("" + config.centerZ);
        levelSeed.setText("" + config.levelSeed);

        // general tab
        extendedDistance.setText("" + config.extendedRenderDistance);
        measureRenderDistance.setSelected(config.measureRenderDistance);
        markUnsaved.setSelected(!config.disableMarkUnsavedChunks);
        overviewZoom.setText("" + config.zoomLevel);

        // auth tab
        minecraftUsername.setText(config.username);
        accessToken.setText(config.accessToken);

        numeric(Arrays.asList(portRemote, portLocal, centerX, centerZ, levelSeed, extendedDistance, overviewZoom));
        disableWhenRunning(Arrays.asList(server, portRemote, portLocal, centerX, centerZ, worldOutputDir));

        authTooltip = new Tooltip("");
        GuiManager.bindTooltip(authDetailsVerifyLabel, authTooltip);

        authHelpLink.setOnAction(actionEvent -> GuiManager.openLink("https://github.com/mircokroon/minecraft-world-downloader/wiki/Authentication"));

        accessToken.textProperty().addListener((ov, oldV, newV) -> {
            // trim invalid characters, remove accessToken at front in case they copied the entire line
            accessToken.setText(newV.trim()
                    .replaceAll("[^A-Za-z0-9\\-.]*", "")
                    .replaceFirst("accessToken", ""));
        });

        handleDataValidation();
        handleErrorTab();
        handleResizing();
    }

    private void handleDataValidation() {
        // disable button when field is empty
        server.textProperty().addListener((ov, oldV, newV) -> {
            updateSaveButtonState();
        });

        // verify auth details on focus loss
        minecraftDir.focusedProperty().addListener((ov, oldVal, newVal) -> {
            if (oldVal && !newVal) {
                verifyAuthDetails();
            }
        });
        verifyAuthDetails();

        // verify port on focus loss
        portLocal.focusedProperty().addListener((ov, oldVal, newVal) -> {
            verifyLocalPort();
        });
        verifyLocalPort();
    }

    private void updateSaveButtonState() {
        int len = server.getText() == null ? 0 : server.getText().length();
        saveButton.setDisable(len == 0 || portInUse);
    }


    private void handleResizing() {
        // make sure the error output resizes with the window
        Platform.runLater(() -> {
            GuiManager.getStage().heightProperty().addListener((ov, oldVal, newVal) -> {
                double delta = newVal.doubleValue() - oldVal.doubleValue();
                tabPane.setPrefHeight(tabPane.getPrefHeight() + delta);
                saveButton.setLayoutY(saveButton.getLayoutY() + delta);
            });

            GuiManager.getStage().widthProperty().addListener((ov, oldVal, newVal) -> {
                double delta = newVal.doubleValue() - oldVal.doubleValue();
                tabPane.setPrefWidth(tabPane.getPrefWidth() + delta);
                saveButton.setLayoutX(saveButton.getLayoutX() + delta);
            });
        });
    }

    private void verifyLocalPort() {
        if (!isPortAvailable(Integer.parseInt(portLocal.getText()))) {
            portVerifyLabel.setText("Port in use!");
            portInUse = true;
        } else {
            portVerifyLabel.setText("");
            portInUse = false;
        }
        updateSaveButtonState();
    }

    private void verifyAuthDetails() {
        String path = minecraftDir.getText();
        AuthStatus status = ClientAuthenticator.authDetailsValid(path);

        authDetailsVerifyLabel.getStyleClass().clear();
        if (status.isExpired()) {
            authDetailsVerifyLabel.getStyleClass().add("label-warn");
            authDetailsVerifyLabel.setText("Expired?");
            authTooltip.setText("Your authentication details are more than a day old. If you are using a custom launcher, enter your token.");
        } else if (status.isValid()) {
            authDetailsVerifyLabel.getStyleClass().add("label-valid");
            authDetailsVerifyLabel.setText("Found");
            authTooltip.setText("Details found. Authentication should be automatic.");
        } else {
            authDetailsVerifyLabel.getStyleClass().add("label-err");
            authDetailsVerifyLabel.setText("Not found");
            authTooltip.setText("Cannot find authentication files. Enter the correct location, \nor open the Authentication tab to enter your details.");
        }
    }

    public void refreshErrorTab() {
        tabPane.getTabs().add(errTab);
        handleErrorTab();
    }

    private void handleErrorTab() {
        // remove error tag if there is no errors
        if (!GuiManager.hasErrors()) {
            tabPane.getTabs().remove(errTab);
            return;
        } else {
            tabPane.getSelectionModel().select(errTab);
        }
        errOutput.setWrapText(true);

        ObservableList<String> messages = GuiManager.getMessages();
        errOutput.setText(join(messages));

        try {

            GuiManager.getMessages().addListener((ListChangeListener<String>) change -> {
                errOutput.setText(join(messages));
            });
        } catch (Exception ex) {
            ex.printStackTrace(System.out);
        }
    }

    private String join(ObservableList<String> list) {
        return list.stream().reduce((a, b) -> {
            if (a.length() == 0 || a.equals("\n")) {
                return b;
            } else if (b.length() == 0 || b.equals("\n")) {
                return a;
            } else {
                return a + "\n " + b;
            }
        }).orElse("");
    }

    private void disableWhenRunning(List<TextField> numericFields) {
        if (!config.isStarted()) {
            return;
        }
        numericFields.forEach(field -> field.setDisable(true));
    }

    private void numeric(List<TextField> numericFields) {
        numericFields.forEach(field -> field.textProperty().addListener((observable, oldVal, newVal) -> {
            if (!newVal.matches("\\d*")) {
                field.setText(oldVal);
            }
        }));
    }

    public void saveSettings(ActionEvent actionEvent) {
        // connection tab
        config.server = server.getText();
        config.portRemote = Integer.parseInt(portRemote.getText());
        config.portLocal = Integer.parseInt(portLocal.getText());
        config.minecraftDir = minecraftDir.getText();

        // output tab
        config.worldOutputDir = worldOutputDir.getText();
        config.centerX = Integer.parseInt(centerX.getText());
        config.centerZ = Integer.parseInt(centerZ.getText());
        config.levelSeed = Integer.parseInt(levelSeed.getText());

        // general tab
        config.extendedRenderDistance = Integer.parseInt(extendedDistance.getText());
        config.measureRenderDistance = measureRenderDistance.isSelected();
        config.disableMarkUnsavedChunks = !markUnsaved.isSelected();
        config.zoomLevel = Integer.parseInt(overviewZoom.getText());

        // auth tab
        config.username = minecraftUsername.getText();
        config.accessToken = accessToken.getText();

        if (!config.isStarted()) {
            if (!isPortAvailable(config.portLocal)) {
                System.err.println("Port in use");
                return;
            }
        }
        config.settingsComplete();
        GuiManager.closeSettings();
    }

    public boolean isPortAvailable(int port) {
        if (config.isStarted()) { return true; }

        try (ServerSocket ss = new ServerSocket(port)) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
