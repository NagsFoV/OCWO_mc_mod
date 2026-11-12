package io.github.nagsfov.ocwo.client;

import io.github.nagsfov.ocwo.OcwoMixinPlugin;
import io.github.nagsfov.ocwo.OcwoBootConfig;

public class OcwoClientMixinPlugin extends OcwoMixinPlugin {

	@Override
	public boolean shouldApplyMixin(String target, String mixin) {
		if (OcwoBootConfig.INSTANCE.isServerOnly()) return false;
		return super.shouldApplyMixin(target, mixin);
	}
}
