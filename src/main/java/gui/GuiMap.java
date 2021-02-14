package gui;

import config.Config;
import game.data.coordinates.Coordinate2D;
import game.data.coordinates.CoordinateDim2D;
import game.data.coordinates.CoordinateDouble3D;
import game.data.WorldManager;
import game.data.chunk.Chunk;
import game.data.dimension.Dimension;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.beans.value.ChangeListener;
import javafx.embed.swing.SwingFXUtils;
import javafx.fxml.FXML;
import javafx.scene.SnapshotParameters;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.util.Duration;

import javax.imageio.ImageIO;
import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;

/**
 * Controller for the map scene. Contains a canvas for chunks which is redrawn only when required, and one for entities
 * which can be redrawn any moment.
 */
public class GuiMap {
    public Canvas chunkCanvas;
    public Canvas entityCanvas;
    public Label helpLabel;
    private boolean playerHasConnected = false;
    private boolean showErrorPrompt = false;

    private CoordinateDouble3D playerPos;
    private double playerRotation;

    private static final WritableImage BLACK = new WritableImage(1, 1);
    static {
        BLACK.getPixelWriter().setColor(0, 0, Color.BLACK);
    }

    private static final Image NONE = new WritableImage(1, 1);
    private static final ChunkImage NO_IMG = new ChunkImage(NONE, true);
    private final Color BACKGROUND_COLOR = new Color(.2, .2, .2, 1);
    private final Color EXISTING_COLOR = new Color(.8, .8, .8, .2);
    private final Color UNSAVED_COLOR = new Color(1, 0, 0, .4);
    private int renderDistanceX;
    private int renderDistanceZ;
    private Bounds bounds;
    private int gridSize = 0;
    private Map<Dimension, Map<Coordinate2D, ChunkImage>> chunkDimensions = new ConcurrentHashMap<>();
    private Map<Coordinate2D, ChunkImage> chunkMap;
    private Collection<Coordinate2D> drawableChunks = new ConcurrentLinkedQueue<>();

    ReadOnlyDoubleProperty width;
    ReadOnlyDoubleProperty height;

    private boolean hasChanged = false;

    @FXML
    void initialize() {
        Platform.runLater(() -> WorldManager.getInstance().outlineExistingChunks());

        setDimension(Dimension.OVERWORLD);
        setupCanvasProperties();

        GuiManager.setGraphicsHandler(this);
        WorldManager.getInstance().setPlayerPosListener(this::updatePlayerPos);

        setupContextMenu();
        bindScroll();

        setupHelpLabel();

    }

    private void setupHelpLabel() {
        if (!playerHasConnected) {
            helpLabel.setText(Config.getConnectionDetails().getConnectionHint());
        }
        entityCanvas.setOnMouseEntered(e -> {
            if (playerHasConnected && !showErrorPrompt) {
                helpLabel.setText("Right-click to open context menu. Scroll to zoom.");
            }
        });
        entityCanvas.setOnMouseExited(e -> {
            if (playerHasConnected && !showErrorPrompt) {
                helpLabel.setText("");
            }
        });
    }

    private void setupCanvasProperties() {
        chunkCanvas.getGraphicsContext2D().setImageSmoothing(false);
        entityCanvas.getGraphicsContext2D().setImageSmoothing(true);

        Pane p = (Pane) chunkCanvas.getParent();
        width = p.widthProperty();
        height = p.heightProperty();

        chunkCanvas.widthProperty().bind(width);
        chunkCanvas.heightProperty().bind(height);

        entityCanvas.widthProperty().bind(width);
        entityCanvas.heightProperty().bind(height);

        height.addListener((ChangeListener<? super Number>)  (a, b, c) -> {
            redrawAll();
        });

        // periodically recompute the canvas bounds
        Timeline reload = new Timeline(new KeyFrame(Duration.millis(1000), e -> {
            computeBounds(false);
        }));
        reload.setCycleCount(Animation.INDEFINITE);
        reload.play();

        redrawAll();
    }

    private void setupContextMenu() {
        ContextMenu menu = new RightClickMenu(this);
        entityCanvas.setOnContextMenuRequested(e -> menu.show(entityCanvas, e.getScreenX(), e.getScreenY()));

        entityCanvas.setOnMouseClicked(e -> menu.hide());
    }

    public void clearChunks() {
        chunkMap.clear();
        drawableChunks.clear();

        hasChanged = true;
    }

    private void bindScroll() {
        entityCanvas.setOnScroll(scrollEvent -> {
            int zoom = Config.getZoomLevel();
            if (scrollEvent.getDeltaY() > 0) {
                zoom /= 2;
            } else {
                zoom *= 2;
            }
            if (zoom < 2) { zoom = 2; }
            if (zoom > 1000) { zoom = 1000; }

            Config.setZoomLevel(zoom);
            redrawAll();
        });
    }

    /**
     * Compute the render distance on both axis -- we have two to keep them separate as non-square windows will look
     * bad otherwise.
     */
    private void computeRenderDistance() {
        double ratio = (height.get() / width.get());

        int zoom = Config.getZoomLevel();
        renderDistanceX =  (int) Math.ceil(zoom / ratio);
        renderDistanceZ = (int) Math.ceil(zoom * ratio);
    }

    void setChunkExists(CoordinateDim2D coord) {
        chunkMap.put(coord, NO_IMG);

        hasChanged = true;
    }

    void markChunkSaved(CoordinateDim2D coord) {
        if (chunkMap.containsKey(coord)) {
            chunkMap.get(coord).setSaved(true);
        }

        hasChanged = true;
    }

    void setChunkLoaded(CoordinateDim2D coord, Chunk chunk) {
        if (!playerHasConnected) {
            playerHasConnected = true;

            if (!showErrorPrompt) {
                Platform.runLater(() -> helpLabel.setText(""));
            }
        }

        Image image = chunk.getImage();

        if (image == null) {
            image = NONE;
        }

        chunkMap.put(coord, new ChunkImage(image, chunk.isSaved()));
        drawChunkAsync(coord);

        hasChanged = true;
    }

    void redrawAll() {
        computeRenderDistance();
        this.computeBounds(true);
        redrawCanvas();
        redrawPlayer();
    }

    /**
     * Compute the bounds of the canvas based on the existing chunk data. Will also delete chunks that are out of the
     * set render distance. The computed bounds will be used to determine the scale and positions to draw the chunks to.
     */
    void computeBounds(boolean force) {
        if (force) {
            computeRenderDistance();
        } else if (!hasChanged) {
            return;
        }

        hasChanged = false;

        this.drawableChunks = getChunksInRange(chunkMap.keySet(),renderDistanceX * 2, renderDistanceZ * 2);
        Collection<Coordinate2D> inRangeChunks = getChunksInRange(drawableChunks, renderDistanceX, renderDistanceZ);

        this.bounds = getOverviewBounds(inRangeChunks);

        double gridWidth = width.get() / bounds.getWidth();
        double gridHeight = height.get() / bounds.getHeight();

        gridSize = (int) Math.max(1, Math.round(Math.min(gridWidth, gridHeight)));
        redrawCanvas();
    }

    private void redrawCanvas() {
        GraphicsContext graphics = this.chunkCanvas.getGraphicsContext2D();

        graphics.setStroke(Color.TRANSPARENT);
        graphics.setFill(BACKGROUND_COLOR);
        graphics.fillRect(0, 0, width.get(), height.get());

        for (Coordinate2D coord : drawableChunks) {
            drawChunk(coord);
        }
    }

    private Bounds getOverviewBounds(Collection<Coordinate2D> coordinates) {
        Bounds bounds = new Bounds();
        for (Coordinate2D coordinate : coordinates) {
            bounds.update(coordinate);
        }
        return bounds;
    }

    /**
     * Computes the set of chunks in range, as well as building the set of all chunks we should draw (up to twice the
     * range due to pixels).
     * @return the set of chunks actually in range.
     */
    private Collection<Coordinate2D> getChunksInRange(Collection<Coordinate2D> coords, int rangeX, int rangeZ) {
        Coordinate2D center;
        if (this.playerPos != null) {
            center = this.playerPos.discretize().globalToChunk();
        } else {
            // if no player position is known, calculate the average coordinate
            center = coords.stream().reduce(Coordinate2D::add).orElse(new Coordinate2D(0, 0)).divide(coords.size());
        }

        return coords.parallelStream()
                .filter(coordinate2D -> coordinate2D.isInRange(center, rangeX, rangeZ))
                .collect(Collectors.toSet());
    }


    public void updatePlayerPos(CoordinateDouble3D playerPos, double rot) {
        this.playerPos = playerPos;
        this.playerRotation = rot;

        Platform.runLater(this::redrawPlayer);
    }

    void redrawPlayer() {
        if (playerPos == null) { return; }
        double playerX = ((playerPos.getX() / 16.0 - bounds.getMinX()) * gridSize);
        double playerZ = ((playerPos.getZ() / 16.0 - bounds.getMinZ()) * gridSize);

        GraphicsContext graphics = entityCanvas.getGraphicsContext2D();
        graphics.clearRect(0, 0, width.get(), height.get());

        // direction pointer
        double yaw = Math.toRadians(this.playerRotation + 45);
        double pointerX = (playerX + (3)*Math.cos(yaw) - (3)*Math.sin(yaw));
        double pointerZ = (playerZ + (3)*Math.sin(yaw) + (3)*Math.cos(yaw));

        graphics.setFill(Color.WHITE);
        graphics.setStroke(Color.BLACK);
        graphics.strokeOval((int) playerX - 4, (int) playerZ - 4, 8, 8);
        graphics.strokeOval((int) pointerX - 2, (int) pointerZ - 2, 4, 4);
        graphics.fillOval((int) playerX - 4, (int) playerZ - 4, 8, 8);
        graphics.fillOval((int) pointerX - 2, (int) pointerZ - 2, 4, 4);

        // indicator circle
        graphics.setFill(Color.TRANSPARENT);
        graphics.setStroke(Color.RED);

        graphics.strokeOval((int) playerX - 16, (int) playerZ - 16, 32, 32);
    }

    private void drawChunk(Coordinate2D pos) {
        drawChunk(chunkCanvas.getGraphicsContext2D(), pos, this.bounds, this.gridSize, true);
    }

    private void drawChunk(GraphicsContext graphics, Coordinate2D pos, Bounds bounds, int gridSize, boolean drawBlack) {
        ChunkImage chunkImage = chunkMap.get(pos);

        int drawX = (pos.getX() - bounds.getMinX()) * gridSize;
        int drawY = (pos.getZ() - bounds.getMinZ()) * gridSize;
        if (chunkImage.getImage() == NONE) {
            graphics.setLineWidth(1);
            graphics.setFill(EXISTING_COLOR);
            graphics.setStroke(Color.WHITE);

            // offset by 1 since the stroke is centered on the border, not inside the shape
            graphics.strokeRect(drawX + 1, drawY + 1, gridSize - 1, gridSize - 1);
            graphics.fillRect(drawX, drawY,gridSize - 1, gridSize - 1);
        } else {
            // draw black before drawing chunk so that we can tell void from missing chunks
            if (drawBlack) {
                graphics.drawImage(BLACK, drawX, drawY, gridSize, gridSize);
            }
            graphics.drawImage(chunkImage.getImage(), drawX, drawY, gridSize, gridSize);

            // if the chunk wasn't saved yet, mark it as such
            if (Config.markUnsavedChunks() && !chunkImage.isSaved) {
                graphics.setFill(UNSAVED_COLOR);
                graphics.setStroke(Color.TRANSPARENT);
                graphics.fillRect(drawX, drawY, gridSize, gridSize);
            }
        }
    }

    private void drawChunkAsync(Coordinate2D pos) {
        Platform.runLater(() -> drawChunk(pos));
    }

    public void export() {
        Bounds fullBounds = getOverviewBounds(chunkMap.keySet());

        // set size limit so that we don't have memory issues with the output image
        int MAX_SIZE = 2 << 12;

        int width = fullBounds.getWidth() * 16;
        int height = fullBounds.getHeight() * 16;

        // half the size of the output so we keep nice round numbers, until the grid size is down to 1px/chunk
        int gridSize = 16;
        while (gridSize > 1 && (width > MAX_SIZE || height > MAX_SIZE)) {
            width /= 2;
            height /= 2;
            gridSize /= 2;
        }

        Canvas temp = new Canvas(width, height);
        GraphicsContext graphics = temp.getGraphicsContext2D();
        graphics.setImageSmoothing(false);

        // draw each chunk
        for (Map.Entry<Coordinate2D, ChunkImage> entry : chunkMap.entrySet()) {
            drawChunk(graphics, entry.getKey(), fullBounds, gridSize, false);
        }

        SnapshotParameters snapshotParameters = new SnapshotParameters();
        snapshotParameters.setFill(Color.TRANSPARENT);
        WritableImage img = temp.snapshot(snapshotParameters, new WritableImage(width, height));

        try {
            File dest = Paths.get(Config.getWorldOutputDir(), "rendered.png").toFile();
            ImageIO.write(SwingFXUtils.fromFXImage(img, null), "png", dest);
            System.out.println("Saved overview to " + dest.toString());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void setDimension(Dimension dimension) {
        this.chunkMap = chunkDimensions.computeIfAbsent(dimension, (k) -> new ConcurrentHashMap<>());
    }

    public void showErrorMessage() {
        this.helpLabel.setText("An error has occured. 'Right click' -> 'Settings' to view.");
        this.helpLabel.setStyle("-fx-text-fill: red;");
        this.showErrorPrompt = true;
    }

    public void hideErrorMessage() {
        this.helpLabel.setText("");
        this.helpLabel.setStyle("-fx-text-fill: white;");
        this.showErrorPrompt = false;
    }
}

