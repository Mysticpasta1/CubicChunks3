package cubicchunks.cc.mixin.core.common.chunk;

import static cubicchunks.cc.chunk.util.Utils.unsafeCast;

import com.mojang.datafixers.util.Either;
import cubicchunks.cc.chunk.IChunkManager;
import cubicchunks.cc.chunk.ICube;
import cubicchunks.cc.chunk.ICubeHolder;
import cubicchunks.cc.chunk.ICubeHolderListener;
import cubicchunks.cc.chunk.cube.CubePrimer;
import cubicchunks.cc.chunk.cube.CubePrimerWrapper;
import cubicchunks.cc.chunk.cube.Cube;
import cubicchunks.cc.chunk.util.CubePos;
import cubicchunks.cc.network.PacketCubes;
import cubicchunks.cc.network.PacketDispatcher;
import cubicchunks.cc.network.PacketCubeBlockChanges;
import cubicchunks.cc.utils.AddressTools;
import cubicchunks.cc.utils.Coords;
import it.unimi.dsi.fastutil.shorts.ShortArrayList;
import it.unimi.dsi.fastutil.shorts.ShortArraySet;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.network.IPacket;
import net.minecraft.util.Util;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.IChunk;
import net.minecraft.world.lighting.WorldLightManager;
import net.minecraft.world.server.ChunkHolder;
import net.minecraft.world.server.ChunkManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReferenceArray;

import javax.annotation.Nullable;

@Mixin(ChunkHolder.class)
public abstract class MixinChunkHolder implements ICubeHolder {

    @Shadow public abstract ChunkPos getPosition();

    @Shadow private int prevChunkLevel;
    @Shadow private int chunkLevel;

    @Shadow public static ChunkHolder.LocationType getLocationTypeFromLevel(int level) {
        throw new Error("Mixin failed to apply correctly!");
    }

    @Shadow private boolean accessible;

    //@Shadow protected abstract void chain(CompletableFuture<? extends Either<? extends IChunk, ChunkHolder.IChunkLoadingError>> eitherChunk);

    //@Shadow @Final private static CompletableFuture<Either<Chunk, ChunkHolder.IChunkLoadingError>> UNLOADED_CHUNK_FUTURE;
    //@Shadow @Final public static Either<Chunk, ChunkHolder.IChunkLoadingError> UNLOADED_CHUNK;
    //@Shadow private volatile CompletableFuture<Either<Chunk, ChunkHolder.IChunkLoadingError>> entityTickingFuture;
    //@Shadow @Final private ChunkPos pos;

    @Shadow @Final private static List<ChunkStatus> CHUNK_STATUS_LIST;

    //This is either a ChunkTaskPriorityQueueSorter or a SectionTaskPriorityQueueSorter depending on if this is a chunkholder or sectionholder.
    @Shadow(aliases = "field_219327_v") @Final private ChunkHolder.IListener chunkHolderListener;

    @Shadow(aliases = "func_219281_j") public abstract int getCompletedLevel();
    @Shadow(aliases = "func_219275_d") protected abstract void setCompletedLevel(int p_219275_1_);

    private CompletableFuture<ICube> sectionFuture = CompletableFuture.completedFuture((ICube) null);

    private volatile CompletableFuture<Either<Cube, ChunkHolder.IChunkLoadingError>> tickingSectionFuture = UNLOADED_CUBE_FUTURE;
    private volatile CompletableFuture<Either<Cube, ChunkHolder.IChunkLoadingError>> entityTickingSectionFuture = UNLOADED_CUBE_FUTURE;

    private final AtomicReferenceArray<CompletableFuture<Either<ICube, ChunkHolder.IChunkLoadingError>>> cubeFutureByCubeStatus = new AtomicReferenceArray<>(CHUNK_STATUS_LIST.size());

    @Shadow private int skyLightChangeMask;
    @Shadow private int blockLightChangeMask;
    @Shadow private int boundaryMask;

    @Shadow protected abstract void sendTileEntity(World worldIn, BlockPos posIn);

    @Shadow protected abstract void sendToTracking(IPacket<?> packetIn, boolean boundaryOnly);

    @Shadow @Final private WorldLightManager lightManager;
    @Shadow @Final private ChunkHolder.IPlayerProvider playerProvider;
    @Shadow @Final private ChunkPos pos;
    @Shadow @Final public static CompletableFuture<Either<IChunk, ChunkHolder.IChunkLoadingError>> MISSING_CHUNK_FUTURE;
    @Shadow private int field_219318_m;
    private CubePos cubePos;

    private final ShortArraySet changedLocalBlocks = new ShortArraySet();

    /**
     * A future that returns the ChunkSection if it is a border chunk, {@link
     * net.minecraft.world.server.ChunkHolder.IChunkLoadingError#UNLOADED} otherwise.
     */
    private volatile CompletableFuture<Either<Cube, ChunkHolder.IChunkLoadingError>> borderSectionFuture = UNLOADED_CUBE_FUTURE;

    //BEGIN INJECTS:

    @Inject(method = "processUpdates", at = @At("HEAD"), cancellable = true)
    void processUpdates(ChunkManager chunkManagerIn, CallbackInfo ci) {
        /*
         If sectionPos == null, this is a ChunkManager
         else, this is a CubeManager.
         This is being implemented as a mixin, instead of having a specific CubeManager class.
         this.sectionPos is essentially being used as a flag for changing behaviour.
         */

        if(this.cubePos == null)
        {
            return;
        }
        ci.cancel();

        ChunkStatus chunkstatus = ICubeHolder.getCubeStatusFromLevel(this.prevChunkLevel);
        ChunkStatus chunkstatus1 = ICubeHolder.getCubeStatusFromLevel(this.chunkLevel);
        boolean wasFullyLoaded = this.prevChunkLevel <= IChunkManager.MAX_CUBE_LOADED_LEVEL;
        boolean isFullyLoaded = this.chunkLevel <= IChunkManager.MAX_CUBE_LOADED_LEVEL;
        ChunkHolder.LocationType previousLocationType = getLocationTypeFromLevel(this.prevChunkLevel);
        ChunkHolder.LocationType currentLocationType = getLocationTypeFromLevel(this.chunkLevel);
        if (wasFullyLoaded) {
            @SuppressWarnings("MixinInnerClass")
            Either<ICube, ChunkHolder.IChunkLoadingError> either = Either.right(new ChunkHolder.IChunkLoadingError() {
                public String toString() {
                    return "Unloaded ticket level " + cubePos.toString();
                }
            });
            for(int i = isFullyLoaded ? chunkstatus1.ordinal() + 1 : 0; i <= chunkstatus.ordinal(); ++i) {
                CompletableFuture<Either<ICube, ChunkHolder.IChunkLoadingError>> completablefuture = this.cubeFutureByCubeStatus.get(i);
                if (completablefuture != null) {
                    completablefuture.complete(either);
                } else {
                    this.cubeFutureByCubeStatus.set(i, CompletableFuture.completedFuture(either));
                }
            }
        }

        boolean wasBorder = previousLocationType.isAtLeast(ChunkHolder.LocationType.BORDER);
        boolean isBorder = currentLocationType.isAtLeast(ChunkHolder.LocationType.BORDER);
        this.accessible |= isBorder;
        if (!wasBorder && isBorder) {
            this.borderSectionFuture = ((IChunkManager)chunkManagerIn).createSectionBorderFuture((ChunkHolder)(Object)this);
            this.chainSection((CompletableFuture)this.borderSectionFuture);
        }

        if (wasBorder && !isBorder) {
            CompletableFuture<Either<Cube, ChunkHolder.IChunkLoadingError>> completablefuture1 = this.borderSectionFuture;
            this.borderSectionFuture = UNLOADED_CUBE_FUTURE;
            this.chainSection(unsafeCast(completablefuture1.thenApply((chunkLoadingErrorEither) -> {
                return chunkLoadingErrorEither.ifLeft(((IChunkManager)chunkManagerIn)::saveCubeScheduleTicks);
            })));
        }

        boolean wasTicking = previousLocationType.isAtLeast(ChunkHolder.LocationType.TICKING);
        boolean isTicking = currentLocationType.isAtLeast(ChunkHolder.LocationType.TICKING);
        if (!wasTicking && isTicking) {
            this.tickingSectionFuture = ((IChunkManager)chunkManagerIn).createSectionTickingFuture((ChunkHolder)(Object)this);
            this.chainSection(unsafeCast(this.tickingSectionFuture));
        }

        if (wasTicking && !isTicking) {
            this.tickingSectionFuture.complete(UNLOADED_CUBE);
            this.tickingSectionFuture = UNLOADED_CUBE_FUTURE;
        }

        boolean wasEntityTicking = previousLocationType.isAtLeast(ChunkHolder.LocationType.ENTITY_TICKING);
        boolean isEntityTicking = currentLocationType.isAtLeast(ChunkHolder.LocationType.ENTITY_TICKING);
        if (!wasEntityTicking && isEntityTicking) {
            if (this.entityTickingSectionFuture != UNLOADED_CUBE_FUTURE) {
                throw (IllegalStateException) Util.pauseDevMode(new IllegalStateException());
            }

            this.entityTickingSectionFuture = ((IChunkManager)chunkManagerIn).createSectionEntityTickingFuture(this.cubePos);
            this.chainSection(unsafeCast(this.entityTickingSectionFuture));
        }

        if (wasEntityTicking && !isEntityTicking) {
            this.entityTickingSectionFuture.complete(UNLOADED_CUBE);
            this.entityTickingSectionFuture = UNLOADED_CUBE_FUTURE;
        }

        ((ICubeHolderListener)this.chunkHolderListener).onUpdateSectionLevel(this.cubePos, () -> getCompletedLevel(), this.chunkLevel,
                p_219275_1_ -> setCompletedLevel(p_219275_1_));
        this.prevChunkLevel = this.chunkLevel;
    }

    //BEGIN OVERRIDES:

    @Override
    public void setYPos(int yPos) { //Whenever ChunkHolder is instantiated this should be called to finish the construction of the object
        this.cubePos = CubePos.of(getPosition().x, yPos, getPosition().z);
        this.prevChunkLevel = IChunkManager.MAX_CUBE_LOADED_LEVEL + 1;
        this.field_219318_m = this.prevChunkLevel;
    }


    @Override
    public int getYPos()
    {
        return this.cubePos.getY();
    }

    @Override
    public CubePos getCubePos() {
        return cubePos;
    }

    // getChunkIfComplete
    @Nullable
    @Override
    public Cube getCubeIfComplete() {
        CompletableFuture<Either<Cube, ChunkHolder.IChunkLoadingError>> completablefuture = this.tickingSectionFuture;
        Either<Cube, ChunkHolder.IChunkLoadingError> either = completablefuture.getNow(null);
        return either == null ? null : either.left().orElse(null);
    }

    @Override
    public CompletableFuture<Either<Cube, ChunkHolder.IChunkLoadingError>> getSectionEntityTickingFuture() {
        return this.entityTickingSectionFuture;
    }

    // chain
    @Override
    public void chainSection(CompletableFuture<? extends Either<? extends ICube, ChunkHolder.IChunkLoadingError>> eitherChunk) {
        this.sectionFuture = this.sectionFuture.thenCombine(eitherChunk, (cube, cubeOrError) -> {
            return cubeOrError.map((existingCube) -> {
                return existingCube;
            }, (error) -> {
                return cube;
            });
        });
    }

    // func_219301_a
    @Override
    public CompletableFuture<Either<ICube, ChunkHolder.IChunkLoadingError>> getSectionFuture(ChunkStatus chunkStatus) {
        CompletableFuture<Either<ICube, ChunkHolder.IChunkLoadingError>> completablefuture =
                this.cubeFutureByCubeStatus.get(chunkStatus.ordinal());
        return completablefuture == null ? MISSING_CUBE_FUTURE : completablefuture;
    }

    // func_219302_f
    @Override
    public CompletableFuture<ICube> getCurrentCubeFuture() {
        return sectionFuture;
    }

    // func_219301_a
    public CompletableFuture<Either<ICube, ChunkHolder.IChunkLoadingError>> getFutureByCubeStatus(ChunkStatus chunkStatus) {
        CompletableFuture<Either<ICube, ChunkHolder.IChunkLoadingError>> completablefuture =
                this.cubeFutureByCubeStatus.get(chunkStatus.ordinal());
        return completablefuture == null ? MISSING_CUBE_FUTURE : completablefuture;
    }
    // func_225410_b
    @Override public CompletableFuture<Either<ICube, ChunkHolder.IChunkLoadingError>> getFutureHigherThanCubeStatus(ChunkStatus chunkStatus) {
        return ICubeHolder.getCubeStatusFromLevel(this.chunkLevel).isAtLeast(chunkStatus) ? this.getFutureByCubeStatus(chunkStatus) : MISSING_CUBE_FUTURE;
    }

    // func_219276_a
    @Override
    public CompletableFuture<Either<ICube, ChunkHolder.IChunkLoadingError>> createCubeFuture(ChunkStatus status, ChunkManager chunkManager) {
        int statusOrdinal = status.ordinal();
        CompletableFuture<Either<ICube, ChunkHolder.IChunkLoadingError>> completablefuture = this.cubeFutureByCubeStatus.get(statusOrdinal);
        if (completablefuture != null) {
            Either<ICube, ChunkHolder.IChunkLoadingError> either = completablefuture.getNow(null);
            if (either == null || either.left().isPresent()) {
                return completablefuture;
            }
        }

        if (ICubeHolder.getCubeStatusFromLevel(this.chunkLevel).isAtLeast(status)) {
            CompletableFuture<Either<ICube, ChunkHolder.IChunkLoadingError>> completablefuture1 =
                    ((IChunkManager)chunkManager).createCubeFuture((ChunkHolder)(Object)this, status);
            this.chainSection(completablefuture1);
            this.cubeFutureByCubeStatus.set(statusOrdinal, completablefuture1);
            return completablefuture1;
        } else {
            return completablefuture == null ? MISSING_CUBE_FUTURE : completablefuture;
        }
    }

    // TODO: this needs to be completely replaced for proper section handling
    /**
     * @author Barteks2x**
     * @reason height limits
     */
    @Overwrite
    public void markBlockChanged(int x, int y, int z) {
        if (cubePos == null) {
            throw new IllegalStateException("Why is this getting called?");
        }
        Cube cube = getCubeIfComplete();
        if (cube == null) {
            return;
        }
        changedLocalBlocks.add((short) AddressTools.getLocalAddress(x, y, z));
    }

    /**
     * @author Barteks2x
     * @reason replace packet classes with CC packets
     */
    @Overwrite
    public void sendChanges(Chunk chunkIn) {
        if (cubePos != null) {
            throw new IllegalStateException("Why is this getting called?");
        }
        // noop
    }

    @Override
    public void sendChanges(Cube cube) {
        if (cubePos == null) {
            throw new IllegalStateException("sendChanges(WorldSection) called on column holder!");
        }
        if (this.changedLocalBlocks.isEmpty() && this.skyLightChangeMask == 0 && this.blockLightChangeMask == 0) {
            return;
        }
        World world = cube.getWorld();
        // if (this.skyLightChangeMask != 0 || this.blockLightChangeMask != 0) {
        //     this.sendToTracking(new SUpdateLightPacket(section.getPos(), this.lightManager, this.skyLightChangeMask & ~this.boundaryMask,
        //             this.blockLightChangeMask & ~this.boundaryMask), true);
        //     int i = this.skyLightChangeMask & this.boundaryMask;
        //     int j = this.blockLightChangeMask & this.boundaryMask;
        //     if (i != 0 || j != 0) {
        //         this.sendToTracking(new SUpdateLightPacket(section.getPos(), this.lightManager, i, j), false);
        //     }
        //     this.skyLightChangeMask = 0;
        //     this.blockLightChangeMask = 0;
        //     this.boundaryMask &= ~(this.skyLightChangeMask & this.blockLightChangeMask);
        // }

        ShortArraySet changed = changedLocalBlocks;
        int changedBlocks = changed.size();
        if (changed.size() >= net.minecraftforge.common.ForgeConfig.SERVER.clumpingThreshold.get()) {
            this.boundaryMask = -1;
        }

        if (changedBlocks >= net.minecraftforge.common.ForgeConfig.SERVER.clumpingThreshold.get()) {
            this.sendToTracking(new PacketCubes(Collections.singletonList(cube)), false);
        } else if (changedBlocks != 0) {
            this.sendToTracking(new PacketCubeBlockChanges(cube, new ShortArrayList(changed)), false);
            for (short pos : changed) {
                BlockPos blockpos1 = new BlockPos(
                        this.cubePos.blockX(AddressTools.getLocalX(pos)),
                        this.cubePos.blockY(AddressTools.getLocalY(pos)),
                        this.cubePos.blockZ(AddressTools.getLocalZ(pos)));
                if (world.getBlockState(blockpos1).hasTileEntity()) {
                    this.sendTileEntity(world, blockpos1);
                }
            }
        }
        changedLocalBlocks.clear();
    }

    private void sendToTracking(Object packetIn, boolean boundaryOnly) {
        this.playerProvider.getTrackingPlayers(this.pos, boundaryOnly)
                .forEach(player -> PacketDispatcher.sendTo(packetIn, player));
    }

    // func_219294_a
    @Override
    public void onSectionWrapperCreated(CubePrimerWrapper primer) {
        for(int i = 0; i < this.cubeFutureByCubeStatus.length(); ++i) {
            CompletableFuture<Either<ICube, ChunkHolder.IChunkLoadingError>> future = this.cubeFutureByCubeStatus.get(i);
            if (future != null) {
                Optional<ICube> optional = future.getNow(MISSING_CUBE).left();
                if (optional.isPresent() && optional.get() instanceof CubePrimer) {
                    this.cubeFutureByCubeStatus.set(i, CompletableFuture.completedFuture(Either.left(primer)));
                }
            }
        }

        this.chainSection(CompletableFuture.completedFuture(Either.left((ICube) primer.getCube())));
    }
}
