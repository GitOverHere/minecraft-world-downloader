package packets.handler;

import config.Config;
import config.Option;
import config.Version;
import game.data.WorldManager;
import game.data.coordinates.Coordinate3D;
import game.data.coordinates.CoordinateDim2D;
import game.data.coordinates.CoordinateDouble3D;
import game.data.dimension.Dimension;
import game.data.entity.EntityRegistry;
import game.data.entity.MobEntity;
import game.data.entity.ObjectEntity;
import packets.handler.version.*;
import proxy.ConnectionManager;
import se.llbit.nbt.SpecificTag;

import java.util.HashMap;
import java.util.Map;

public class ClientBoundGamePacketHandler extends PacketHandler {
    private final HashMap<String, PacketOperator> operations = new HashMap<>();
    public ClientBoundGamePacketHandler(ConnectionManager connectionManager) {
        super(connectionManager);

        WorldManager worldManager = WorldManager.getInstance();
        EntityRegistry entityRegistry = WorldManager.getInstance().getEntityRegistry();

        operations.put("SetEntityData", provider -> {
            entityRegistry.addMetadata(provider);
            return true;
        });

        operations.put("SetEquipment", provider -> {
            entityRegistry.addEquipment(provider);
            return true;
        });

        operations.put("AddMob", provider -> {
            entityRegistry.addEntity(provider, MobEntity::parse);
            return true;
        });

        operations.put("AddEntity", provider -> {
            entityRegistry.addEntity(provider, ObjectEntity::parse);
            return true;
        });

        operations.put("AddPlayer", provider -> {
            entityRegistry.addPlayer(provider);
            return true;
        });

        operations.put("RemoveEntities", provider -> {
            entityRegistry.destroyEntities(provider);
            return true;
        });

        operations.put("MoveEntityPos", provider -> {
            entityRegistry.updatePositionRelative(provider);
            return true;
        });
        operations.put("MoveEntityPosRot", provider -> {
            entityRegistry.updatePositionRelative(provider);
            return true;
        });
        operations.put("TeleportEntity", provider -> {
            entityRegistry.updatePositionAbsolute(provider);
            return true;
        });

        operations.put("MapItemData", provider -> {
            worldManager.getMapRegistry().readMap(provider);
            return true;
        });

        operations.put("Login", provider -> {
            provider.readInt();
            provider.readNext();
            int dimensionEnum = provider.readInt();

            worldManager.setDimension(Dimension.fromId(dimensionEnum));

            return true;
        });

        operations.put("Respawn", provider -> {
            int dimensionEnum = provider.readInt();
            worldManager.setDimension(Dimension.fromId(dimensionEnum));
            worldManager.getEntityRegistry().reset();
            return true;
        });

        operations.put("LevelChunk", provider -> {
            try {
                worldManager.getChunkFactory().addChunk(provider);
            } catch (Exception ex) {
                ex.printStackTrace();
            }
            return true;
        });

        operations.put("LightUpdate", provider -> {
            worldManager.updateLight(provider);

            return true;
        });

        operations.put("BlockUpdate", provider -> {
            WorldManager.getInstance().blockChange(provider);
            return true;
        });
        operations.put("SectionBlocksUpdate", provider -> {
            int x = provider.readInt();
            int z = provider.readInt();
            worldManager.multiBlockChange(new Coordinate3D(x, 0, z), provider);

            return true;
        });

        operations.put("ForgetLevelChunk", provider -> {
            CoordinateDim2D co = new CoordinateDim2D(provider.readInt(), provider.readInt(), WorldManager.getInstance().getDimension());
            worldManager.unloadChunk(co);
            return Config.getExtendedRenderDistance() == 0;
        });

        operations.put("BlockEntityData", provider -> {
            Coordinate3D position = provider.readCoordinates();
            byte action = provider.readNext();
            SpecificTag entityData = provider.readNbtTag();

            worldManager.getChunkFactory().updateTileEntity(position, entityData);
            return true;
        });

        operations.put("OpenScreen", provider -> {
            int windowId = provider.readNext();
            String windowType = provider.readString();
            String windowTitle = provider.readChat();

            int numSlots = provider.readNext() & 0xFF;

            worldManager.getContainerManager().openWindow_1_12(windowId, numSlots, windowTitle);

            return true;
        });
        operations.put("ContainerClose", provider -> {
            worldManager.getContainerManager().closeWindow(provider.readNext());
            return true;
        });

        operations.put("ContainerSetContent", provider -> {
            int windowId = provider.readNext();

            int count = provider.readShort();
            worldManager.getContainerManager().items(windowId, count, provider);

            return true;
        });

        operations.put("update_view_distance", provider -> {
            System.out.println("Server tried to change view distance to " + provider.readVarInt());
           return false;
        });
    }

    public static PacketHandler of(ConnectionManager connectionManager) {
        return Config.versionReporter().select(PacketHandler.class,
                Option.of(Version.V1_18, () -> new ClientBoundGamePacketHandler_1_18(connectionManager)),
                Option.of(Version.V1_17, () -> new ClientBoundGamePacketHandler_1_17(connectionManager)),
                Option.of(Version.V1_16, () -> new ClientBoundGamePacketHandler_1_16(connectionManager)),
                Option.of(Version.V1_15, () -> new ClientBoundGamePacketHandler_1_15(connectionManager)),
                Option.of(Version.V1_14, () -> new ClientBoundGamePacketHandler_1_14(connectionManager)),
                Option.of(Version.ANY, () -> new ClientBoundGamePacketHandler(connectionManager))
        );
    }

    @Override
    public Map<String, PacketOperator> getOperators() {
        return operations;
    }

    @Override
    public boolean isClientBound() {
        return true;
    }
}
