package com.assemblylib.assembly;

import javax.annotation.Nullable;

import com.assemblylib.blockentity.ServoMotorBlockEntity;

/**
 * Implemented by the wrapped levels that host a assembly's blocks — the server
 * {@link AssemblySimServerLevel} and the client {@code AssemblyRenderLevel}. Lets a Servo
 * Motor reconstructed <em>inside</em> another assembly discover the motor that hosts it, so its
 * {@link AssemblyTransform} can compose through the parent's pose (nested assemblys).
 *
 * <p>Dist-safe by design: {@link ServoMotorBlockEntity} dispatches on this interface rather than on
 * the client-only render level type, keeping the block entity free of client references.
 */
public interface AssemblyHostLevel {

	/** The Servo Motor whose assembly this level hosts, or {@code null} if it cannot be resolved. */
	@Nullable
	ServoMotorBlockEntity getAssemblyHostMotor();
}
