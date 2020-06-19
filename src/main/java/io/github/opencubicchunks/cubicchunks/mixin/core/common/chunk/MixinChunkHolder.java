package io.github.opencubicchunks.cubicchunks.mixin.core.common.chunk;

import static io.github.opencubicchunks.cubicchunks.chunk.util.Utils.unsafeCast;

import com.mojang.datafixers.util.Either;
import io.github.opencubicchunks.cubicchunks.chunk.IChunkManager;
import io.github.opencubicchunks.cubicchunks.chunk.ICube;
import io.github.opencubicchunks.cubicchunks.chunk.ICubeHolder;
import io.github.opencubicchunks.cubicchunks.chunk.cube.Cube;
import io.github.opencubicchunks.cubicchunks.chunk.cube.CubePrimer;
import io.github.opencubicchunks.cubicchunks.chunk.cube.CubePrimerWrapper;
import io.github.opencubicchunks.cubicchunks.chunk.util.CubePos;
import io.github.opencubicchunks.cubicchunks.network.PacketCubeBlockChanges;
import io.github.opencubicchunks.cubicchunks.network.PacketCubes;
import io.github.opencubicchunks.cubicchunks.network.PacketDispatcher;
import io.github.opencubicchunks.cubicchunks.utils.AddressTools;
import it.unimi.dsi.fastutil.shorts.ShortArrayList;
import it.unimi.dsi.fastutil.shorts.ShortArraySet;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.IChunk;
import net.minecraft.world.lighting.WorldLightManager;
import net.minecraft.world.server.ChunkHolder;
import net.minecraft.world.server.ChunkManager;
import org.spongepowered.asm.mixin.Dynamic;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.BiConsumer;

import javax.annotation.Nullable;

@Mixin(ChunkHolder.class)
public abstract class MixinChunkHolder implements ICubeHolder {

    @Shadow private int chunkLevel;
    @Shadow private ChunkPos pos;

    // these are using java type erasure as a feature - because the generic type information
    // doesn't exist at runtime, we can shadow those fields with different generic types
    // and as long as we are consistent, we can use them with different types than the declaration in the original class
    @Shadow(aliases = "field_219312_g") @Final private AtomicReferenceArray<CompletableFuture<Either<ICube, ChunkHolder.IChunkLoadingError>>> futureByStatus;
    @Shadow private volatile CompletableFuture<Either<Cube, ChunkHolder.IChunkLoadingError>> tickingFuture;
    @Shadow private volatile CompletableFuture<Either<Cube, ChunkHolder.IChunkLoadingError>> entityTickingFuture;
    @Shadow(aliases = "field_219315_j") private CompletableFuture<ICube> chunkFuture;


    @Shadow private int skyLightChangeMask;
    @Shadow private int blockLightChangeMask;
    @Shadow private int boundaryMask;

    @Shadow protected abstract void sendTileEntity(World worldIn, BlockPos posIn);

    @Shadow @Final private ChunkHolder.IPlayerProvider playerProvider;

    @Shadow protected abstract void chain(
            CompletableFuture<? extends Either<? extends IChunk, ChunkHolder.IChunkLoadingError>> eitherChunk);

    @Shadow public static ChunkStatus getChunkStatusFromLevel(int level) {
        throw new Error("Mixin failed to apply");
    }

    @Shadow public abstract CompletableFuture<Either<IChunk, ChunkHolder.IChunkLoadingError>> func_219301_a(ChunkStatus p_219301_1_);

    @Shadow(aliases = "func_219276_a")
    public abstract CompletableFuture<Either<ICube, ChunkHolder.IChunkLoadingError>> createChunkFuture(ChunkStatus chunkStatus,
            ChunkManager chunkManager);

    @SuppressWarnings("unused")
    private CubePos cubePos; // set from ASM

    private final ShortArraySet changedLocalBlocks = new ShortArraySet();

    //BEGIN INJECTS:

    // target generated by ASM
    @Dynamic("Generated by ASM by copying and transforming original constructor")
    @Inject(method = "<init>(Lio/github/opencubicchunks/cubicchunks/chunk/util/CubePos;ILnet/minecraft/world/lighting/WorldLightManager;"
            + "Lnet/minecraft/world/server/ChunkHolder$IListener;Lnet/minecraft/world/server/ChunkHolder$IPlayerProvider;)V",
            at = @At("RETURN")
    )
    public void onConstructCubeHolder(CubePos cubePosIn, int levelIn, WorldLightManager lightManagerIn, ChunkHolder.IListener p_i50716_4_,
            ChunkHolder.IPlayerProvider playerProviderIn, CallbackInfo ci) {
        this.pos = cubePosIn.asChunkPos();
    }

    // used from ASM
    @SuppressWarnings("unused") private static ChunkStatus getCubeStatusFromLevel(int cubeLevel) {
        return ICubeHolder.getCubeStatusFromLevel(cubeLevel);
    }

    @Inject(method = "processUpdates", at = @At("HEAD"), cancellable = true)
    void processUpdates(ChunkManager chunkManagerIn, CallbackInfo ci) {
        /*
         If sectionPos == null, this is a ChunkManager
         else, this is a CubeManager.
         This is being implemented as a mixin, instead of having a specific CubeManager class.
         this.sectionPos is essentially being used as a flag for changing behaviour.
         */
        if(this.cubePos == null) {
            return;
        }
        ci.cancel();
        processCubeUpdates(chunkManagerIn);
    }

    @Override
    public CubePos getCubePos() {
        return cubePos;
    }

    // getChunkIfComplete
    @Nullable
    @Override
    public Cube getCubeIfComplete() {
        CompletableFuture<Either<Cube, ChunkHolder.IChunkLoadingError>> completablefuture = this.tickingFuture;
        Either<Cube, ChunkHolder.IChunkLoadingError> either = completablefuture.getNow(null);
        return either == null ? null : either.left().orElse(null);
    }

    @Override
    public CompletableFuture<Either<Cube, ChunkHolder.IChunkLoadingError>> getCubeEntityTickingFuture() {
        return this.entityTickingFuture;
    }

    // func_219301_a
    @Override
    public CompletableFuture<Either<ICube, ChunkHolder.IChunkLoadingError>> getCubeFuture(ChunkStatus chunkStatus) {
        return unsafeCast(func_219301_a(chunkStatus));
    }

    // func_219302_f
    @Override
    public CompletableFuture<ICube> getCurrentCubeFuture() {
        return chunkFuture;
    }

    // func_225410_b
    @Override public CompletableFuture<Either<ICube, ChunkHolder.IChunkLoadingError>> getFutureHigherThanCubeStatus(ChunkStatus chunkStatus) {
        return ICubeHolder.getCubeStatusFromLevel(this.chunkLevel).isAtLeast(chunkStatus) ?
                unsafeCast(this.func_219301_a(chunkStatus)) : // func_219301_a = getFutureByCubeStatus
                MISSING_CUBE_FUTURE;
    }


    private AtomicReferenceArray<ArrayList<BiConsumer<Either<ICube, ChunkHolder.IChunkLoadingError>, Throwable>>> listenerLists =
            new AtomicReferenceArray<>(ChunkStatus.getAll().size());


    // func_219276_a
    @Redirect(method = "func_219276_a", at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/server/ChunkHolder;getChunkStatusFromLevel(I)Lnet/minecraft/world/chunk/ChunkStatus;"
    ))
    private ChunkStatus getChunkStatusFromLevelRedirect(int level) {
        if (cubePos == null) {
            return getChunkStatusFromLevel(level);
        } else {
            return getCubeStatusFromLevel(level);
        }
    }

    @Redirect(method = "func_219276_a", at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/server/ChunkManager;func_219244_a(Lnet/minecraft/world/server/ChunkHolder;Lnet/minecraft/world/chunk/ChunkStatus;)Ljava/util/concurrent/CompletableFuture;"
    ))
    private CompletableFuture<?> createChunkOrCubeFuture(ChunkManager chunkManager, ChunkHolder _this, ChunkStatus status) {
        if (cubePos == null) {
            return chunkManager.func_219244_a(_this, status);
        } else {
            return ((IChunkManager) chunkManager).createCubeFuture(_this, status);
        }
    }

    // func_219276_a
    @Override public CompletableFuture<Either<ICube, ChunkHolder.IChunkLoadingError>> createCubeFuture(ChunkStatus chunkStatus, ChunkManager chunkManager) {
        return createChunkFuture(chunkStatus, chunkManager);
    }

    public void addCubeStageListener(ChunkStatus status, BiConsumer<Either<ICube, ChunkHolder.IChunkLoadingError>, Throwable> consumer, ChunkManager chunkManager) {
        CompletableFuture<Either<ICube, ChunkHolder.IChunkLoadingError>> future = createChunkFuture(status, chunkManager);

        if (future.isDone()) {
            consumer.accept(future.getNow(null), null);
        } else {
            List<BiConsumer<Either<ICube, ChunkHolder.IChunkLoadingError>, Throwable>> listenerList = this.listenerLists.get(status.ordinal());
            if (listenerList == null) {

                final ArrayList<BiConsumer<Either<ICube, ChunkHolder.IChunkLoadingError>, Throwable>> listeners = new ArrayList<>();
                future.whenComplete((either, throwable) -> {
                    for (BiConsumer<Either<ICube, ChunkHolder.IChunkLoadingError>, Throwable> listener : listeners) {
                        listener.accept(either, throwable);
                    }
                    listeners.clear();
                    listeners.trimToSize();
                });
                this.listenerLists.set(status.ordinal(), listeners);
                listenerList = listeners;
            }

            listenerList.add(consumer);
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
            throw new IllegalStateException("sendChanges(Cube) called on column holder!");
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
        // TODO: fix block update tracking
        this.playerProvider.getTrackingPlayers(this.cubePos.asChunkPos(), boundaryOnly)
                .forEach(player -> PacketDispatcher.sendTo(packetIn, player));
    }

    // func_219294_a
    @Override
    public void onCubeWrapperCreated(CubePrimerWrapper primer) {
        for(int i = 0; i < this.futureByStatus.length(); ++i) {
            CompletableFuture<Either<ICube, ChunkHolder.IChunkLoadingError>> future = this.futureByStatus.get(i);
            if (future != null) {
                Optional<ICube> optional = future.getNow(MISSING_CUBE).left();
                if (optional.isPresent() && optional.get() instanceof CubePrimer) {
                    this.futureByStatus.set(i, CompletableFuture.completedFuture(Either.left(primer)));
                }
            }
        }

        this.chain(unsafeCast(CompletableFuture.completedFuture(Either.left((ICube) primer.getCube()))));
    }
}
