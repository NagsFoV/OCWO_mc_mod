package io.github.nagsfov.ocwo.util;

/** Minimal 2D float vector (replaces the skdslib class used by PhysEx). */
public record Vec2F(float xf, float yf) {

	public float lengthSquared() {
		return xf * xf + yf * yf;
	}
}
