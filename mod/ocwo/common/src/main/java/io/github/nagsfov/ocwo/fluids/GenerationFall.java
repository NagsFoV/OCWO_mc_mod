package io.github.nagsfov.ocwo.fluids;

/**
 * A falling block (sand, gravel...) that started to fall during the first ticks of a new chunk (see
 * {@link WaterRest#isNewborn}): it belongs to the world generation, so its landing does not wake the water either.
 */
public interface GenerationFall {

	boolean ocwo$isGeneration();

	void ocwo$setGeneration(boolean generation);
}
