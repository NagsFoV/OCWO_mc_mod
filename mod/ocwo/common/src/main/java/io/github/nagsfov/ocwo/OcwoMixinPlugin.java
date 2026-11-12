package io.github.nagsfov.ocwo;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

public class OcwoMixinPlugin implements IMixinConfigPlugin {


	@Override
	public boolean shouldApplyMixin(String target, String mixin) {
		if (!OcwoBootConfig.INSTANCE.isFluidPhysicsEnabled() && mixin.contains("._fluids.")) return false;
		if (!OcwoBootConfig.INSTANCE.isBlockPhysicsEnabled() && mixin.contains("._blocks.")) return false;
		if (!OcwoBootConfig.INSTANCE.isExtraFluidLayerEnabled() && mixin.contains("._layer.")) return false;

		return true;
	}

	@Override
	public void onLoad(String s) {
	}

	@Override
	public void preApply(String s, ClassNode classNode, String s1, IMixinInfo iMixinInfo) {
	}

	@Override
	public void postApply(String s, ClassNode classNode, String s1, IMixinInfo iMixinInfo) {
	}

	@Override
	public String getRefMapperConfig() {
		return null;
	}

	@Override
	public void acceptTargets(Set<String> set, Set<String> set1) {

	}

	@Override
	public List<String> getMixins() {
		return null;
	}

}
