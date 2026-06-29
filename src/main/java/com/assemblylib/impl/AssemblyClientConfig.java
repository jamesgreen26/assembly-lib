package com.assemblylib.impl;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Client-side configuration for AssemblyLib's rendering. Currently exposes a single toggle between
 * the new self-contained baked-mesh renderer (default) and the legacy Flywheel GPU-instanced
 * renderer. The toggle is read per-frame by the renderers, so editing the config (and reloading it)
 * switches renderers without a restart, provided the Flywheel backend is available for the legacy
 * path.
 */
public final class AssemblyClientConfig {

	public static final ModConfigSpec SPEC;
	private static final ModConfigSpec.BooleanValue USE_FLYWHEEL_RENDERER;

	static {
		ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
		builder.comment("AssemblyLib client rendering").push("rendering");
		USE_FLYWHEEL_RENDERER = builder
			.comment(
				"Use the legacy Flywheel GPU-instanced renderer instead of the new baked-mesh renderer.",
				"The Flywheel renderer requires the Flywheel backend to be enabled; with it off it falls",
				"back to per-block vanilla rendering. Default: false (baked-mesh renderer).")
			.define("useFlywheelRenderer", false);
		builder.pop();
		SPEC = builder.build();
	}

	private AssemblyClientConfig() {
	}

	/** Whether to use the legacy Flywheel renderer. Defaults to {@code false} until the config loads. */
	public static boolean useFlywheelRenderer() {
		return SPEC.isLoaded() && USE_FLYWHEEL_RENDERER.get();
	}
}
