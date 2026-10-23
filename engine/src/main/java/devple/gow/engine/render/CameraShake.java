package devple.gow.engine.render;

import devple.gow.engine.game.Tweaks;

/**
 * 카메라 흔들림 (원작 시작 0x1F09C8, 매 프레임 0x1F0A30 — 45 문서). 템플릿 {@code /TweakTemplates/CameraShake/NNN/}
 * (로더 0x1F49F8): Duration, 회전 진폭 (도, #97f053d6), 이동 진폭 (#0ee2a477), 주파수 (Hz, #fc12d3dc).
 * 흔들리는 중이면 새 흔들림은 무시 (cam +0x954 ≠ 0). t += 게임 dt, s = sin(2π·f·t), d = 배율·(D − t)/D (배율 +0x958, 0x1F0A30),
 * 카메라 로컬 X 회전 = A·d·s 도, 로컬 Y 이동 = 16·B·d·s — 카메라 행렬 앞에 곱한다.
 */
public final class CameraShake {

	private static final int H_ROT = 0x97f053d6, H_MOVE = 0x0ee2a477, H_FREQ = 0xfc12d3dc;

	private float duration, rot, move, freq, t;
	/** 배율 +0x958: 시작 0x1F09C8 이 1, 소리 흔들림 0x228C70 → 0x1F0A28 이 값/1024. */
	private float scale = 1;
	private boolean active;

	public boolean active() {
		return active;
	}

	/** 흔들림 시작 (진행 중이면 무시). 템플릿이 없으면 아무것도 안 함. */
	public void start(Tweaks tw, int index) {
		if (active || tw == null) {
			return;
		}
		String n = String.format("/TweakTemplates/CameraShake/%03d/", index);
		float d = tw.f(n, "Duration", 0);
		if (d <= 0) {
			return;
		}
		duration = d;
		rot = f(tw, n, H_ROT);
		move = f(tw, n, H_MOVE);
		freq = f(tw, n, H_FREQ);
		t = 0;
		scale = 1;
		active = true;
	}

	/** 배율 (0x1F0A28) — 진행 중일 때만 의미가 있다. */
	public void scale(float s) {
		scale = s;
	}

	private static float f(Tweaks tw, String node, int hash) {
		var v = tw.rawHash(node, hash);
		return v.isPresent() ? Float.intBitsToFloat(v.getAsInt()) : 0;
	}

	/** 게임 dt 로 진행하고 카메라에 넣는다. */
	public void update(float dt, Camera cam) {
		if (!active) {
			cam.shakePitchDeg = 0;
			cam.shakeDy = 0;
			return;
		}
		t += dt;
		if (t >= duration) {
			active = false;
			cam.shakePitchDeg = 0;
			cam.shakeDy = 0;
			return;
		}
		float s = (float) Math.sin(2 * Math.PI * freq * t), d = scale * (duration - t) / duration;
		cam.shakePitchDeg = rot * d * s;
		cam.shakeDy = 16f * move * d * s;
	}
}
