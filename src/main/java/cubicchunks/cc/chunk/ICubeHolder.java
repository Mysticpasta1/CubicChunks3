package cubicchunks.cc.chunk;

import com.mojang.datafixers.util.Either;
import cubicchunks.cc.chunk.cube.CubePrimerWrapper;
import cubicchunks.cc.chunk.cube.Cube;
import cubicchunks.cc.chunk.util.CubePos;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.IChunk;
import net.minecraft.world.server.ChunkHolder;
import net.minecraft.world.server.ChunkManager;

import java.util.concurrent.CompletableFuture;

public interface ICubeHolder {
    Either<ICube, ChunkHolder.IChunkLoadingError> MISSING_CUBE = Either.right(ChunkHolder.IChunkLoadingError.UNLOADED);
    Either<Cube, ChunkHolder.IChunkLoadingError> UNLOADED_CUBE = Either.right(ChunkHolder.IChunkLoadingError.UNLOADED);
    CompletableFuture<Either<Cube, ChunkHolder.IChunkLoadingError>> UNLOADED_CUBE_FUTURE = CompletableFuture.completedFuture(UNLOADED_CUBE);
    CompletableFuture<Either<ICube, ChunkHolder.IChunkLoadingError>> MISSING_CUBE_FUTURE = CompletableFuture.completedFuture(MISSING_CUBE);


    Cube getCubeIfComplete();

    void setYPos(int yPos);
    CubePos getCubePos();
    int getYPos();

    void chainSection(CompletableFuture<? extends Either<? extends ICube,
            ChunkHolder.IChunkLoadingError>> eitherChunk);

    // func_219276_a
    CompletableFuture<Either<ICube, ChunkHolder.IChunkLoadingError>> createCubeFuture(ChunkStatus chunkStatus, ChunkManager chunkManager);

    CompletableFuture<Either<ICube, ChunkHolder.IChunkLoadingError>> getSectionFuture(ChunkStatus chunkStatus);

    CompletableFuture<Either<Cube, ChunkHolder.IChunkLoadingError>> getSectionEntityTickingFuture();

    // func_219294_a
    void onSectionWrapperCreated(CubePrimerWrapper primer);

    // func_225410_b
    CompletableFuture<Either<ICube, ChunkHolder.IChunkLoadingError>> getFutureHigherThanCubeStatus(ChunkStatus chunkStatus);

    CompletableFuture<Either<ICube, ChunkHolder.IChunkLoadingError>> createFuture(ChunkStatus p_219276_1_, ChunkManager p_219276_2_);

    void sendChanges(Cube cube);
}
