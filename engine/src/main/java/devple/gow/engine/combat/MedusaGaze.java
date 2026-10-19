package devple.gow.engine.combat;

import devple.gow.engine.game.Tweaks;
import devple.gow.engine.input.Input;
import devple.gow.engine.input.PadButton;
import org.joml.Vector3f;

import java.util.List;

/**
 * 메두사의 시선 (MedusaHead, 마법 4). 원작 SCR_MedusaHead 0x198348 → 0x1972E8, 원뿔 0x1964D0/0x1961D8, Freeze 0x1E1778 (35 문서 §2).
 * <ul>
 *   <li>광선: USEHEAD 의 SCRIPT {@code SCR_MedusaHead} 동안 마법 버튼을 누르고 있으면 켜짐. MP 초당 Stage Drain (12.5/10/8.33)</li>
 *   <li>원뿔: 기울기 = (Base − Top)/Distance, 꼭짓점 = 눈 − 방향 × Top/기울기, 길이 (Distance + apexBack) × 16 — 적 반지름 포함 원뿔-구 판정</li>
 *   <li>명중한 적의 동결 게이지 −= Freeze Factor(u16 100/115/125) × 60 × dt; 0 이 되면 석화 ({@link Hittable#addFreeze})</li>
 *   <li>섬광(단계 1·2): ○ 누름(원작 비트 0x10, 추정) + MP ≥ Flash Points(20) → 20 소모, 섬광 원뿔(14/18) 안 적에 8000 한 번</li>
 *   <li>광선이 꺼지면(버튼 뗌) 기술 분기가 PUTAWAYHEAD 로 넘어간다 (영웅 CombatFile)</li>
 * </ul>
 * 시선(LOS) 충돌 검사(0x195FB0)는 생략 [근사].
 */
public final class MedusaGaze {

	/** SCR_MedusaHead / SCR_FlashMedusaHead 의 Hash127Upper. */
	public static final int SCR_HEAD = 0x45fa7bf4, SCR_FLASH = 0x8466f02a;
	/** u16 필드 (덤프가 float 로 잘못 표시): Freeze Factor 100/115/125, Flash Freeze Factor 10000/8000/8000. */
	static final int[] FREEZE = {100, 115, 125}, FLASH_FREEZE = {10000, 8000, 8000};
	/** 원작 영웅 "적 반지름"(vtable +0xBC) 근사, m. */
	static final float ENEMY_RADIUS = 1.5f;
	/** 눈(머리를 든 오른손) 높이 근사 (유닛). */
	static final float EYE_Y = 50f;

	private final Tweaks tw;
	private boolean beam, canHold, wasHead;
	/** 머리 조준 방향 (원작 Aim Speed 0.15/프레임 보간, useHeadTurnLeft/Right 혼합 0x196DB0). */
	private float aimYaw;
	/** 몸 정면 기준 최대 조준 각 [추정: Up/Down Angle Degree 덤프 손상, 하위 바이트 80]. */
	static final float MAX_AIM = (float) Math.toRadians(80);
	private int flashSerial = -1;
	/** 이번 틱에 석화된 대상 수 (테스트용 누적). */
	public int petrified;

	public MedusaGaze(Tweaks tw) {
		this.tw = tw;
	}

	/** 현재 머리 조준 방향 (yaw, 이펙트용). */
	public float aimYaw() {
		return aimYaw;
	}

	/** 락온 대상 쪽으로 조준 보간 (몸 정면 ±80° 안). */
	private void aim(Combatant c, float dt) {
		float body = c.owner().yaw();
		float want = body;
		Hittable t = c.target();
		if (t != null && t.hp() > 0) {
			Vector3f d = new Vector3f(t.position()).sub(c.owner().position());
			if (d.x * d.x + d.z * d.z > 1e-4f) {
				float y = (float) Math.atan2(-d.x, -d.z);
				float rel = (float) Math.IEEEremainder(y - body, Math.PI * 2);
				want = body + Math.max(-MAX_AIM, Math.min(MAX_AIM, rel));
			}
		}
		float k = 1 - (float) Math.pow(1 - 0.15, dt * 60);
		aimYaw += (float) Math.IEEEremainder(want - aimYaw, Math.PI * 2) * k;
	}

	/** USEHEAD(애니 없는 기술) 유지 조건: 마법 버튼 홀드 + MP 충분 (Combatant.setMoveHold). */
	public boolean canHold() {
		return canHold;
	}

	public boolean beamOn() {
		return beam;
	}

	/** @return 이번 틱 소모 MP */
	public float update(Combatant c, Input in, List<? extends Hittable> targets, int stage, float mp, float dt,
						Magic.Sink sink) {
		String n = "/Player/MedusaHead/";
		String st = "Stage " + stage + " ";
		float used = 0;
		boolean head = c.activeScript(SCR_HEAD) != null;
		float per = tw.f(n, st + "Drain", 12.5f) * dt;
		boolean held = in == null || in.held(PadButton.R2);
		canHold = held && mp >= per;
		boolean was = beam;
		beam = head && held && mp >= per;
		if (!head || !wasHead) {
			aimYaw = c.owner().yaw(); // 공중 섬광 등 머리를 들지 않은 경우 = 몸 정면
		}
		wasHead = head;
		if (head) {
			aim(c, dt);
		}
		if (beam && !was) {
			sink.effect(tw.s(n, st + "Effect", "goEyeBeam"), c.owner().position());
		}
		if (beam) {
			used += per;
			float dist = tw.f(n, st + "Distance", 15f), top = tw.f(n, st + "Cone Top Rad", 0.5f),
					base = tw.f(n, st + "Cone Base Rad", 3.25f);
			cone(c, targets, dist, top, base, u16(n, st + "Freeze Factor", FREEZE[stage]) * 60f * dt, sink);
		}
		// 섬광: AIRUSEHEAD 의 SCR_FlashMedusaHead 이벤트 또는 광선 중 ○ 누름
		boolean flashEv = c.activeScript(SCR_FLASH) != null && c.moveSerial() != flashSerial;
		boolean flashBtn = head && in != null && in.pressed(PadButton.CIRCLE);
		if ((flashEv || flashBtn) && tw.f(n, st + "Flash Distance", 0) > 0) {
			flashSerial = c.moveSerial();
			float pts = tw.f(n, st + "Flash Points", 20f);
			if (mp - used >= pts) {
				used += pts;
				sink.sound("SND_MEDUSA_RAY_SHOT", c.owner().position());
				sink.effect(tw.s(n, st + "Flash Effect", "goMedHeadFlash"), c.owner().position());
				cone(c, targets, tw.f(n, st + "Flash Distance", 14f), tw.f(n, st + "Flash Cone Top Rad", 0.5f),
						tw.f(n, st + "Flash Cone Base Rad", 2.5f), u16(n, st + "Flash Freeze Factor", FLASH_FREEZE[stage]), sink);
			} else {
				sink.sound("SND_IG_MAGICOUT", c.owner().position());
			}
		}
		return used;
	}

	private int u16(String node, String key, int def) {
		var r = tw.raw(node, key);
		return r.isPresent() ? r.getAsInt() & 0xFFFF : def;
	}

	/** 원뿔 안 대상에 동결량 적용 (0x1964D0 기하 + 0x196028 원뿔-구). */
	void cone(Combatant c, List<? extends Hittable> targets, float dist, float top, float base, float amount, Magic.Sink sink) {
		float slope = (base - top) / dist;
		float halfAng = (float) Math.atan(slope);
		float apexBack = top / slope;
		float yaw = aimYaw;
		Vector3f dir = new Vector3f((float) -Math.sin(yaw), 0, (float) -Math.cos(yaw));
		Vector3f eye = new Vector3f(c.owner().position()).add(0, EYE_Y, 0);
		Vector3f apex = new Vector3f(eye).fma(-apexBack * Magic.UNIT, dir);
		float len = (dist + apexBack) * Magic.UNIT;
		for (Hittable h : targets) {
			if (h.hp() <= 0 || h.petrified()) {
				continue;
			}
			Vector3f p = new Vector3f(h.position()).add(0, EYE_Y * 0.8f, 0);
			if (inCone(p, apex, dir, halfAng, len, ENEMY_RADIUS * Magic.UNIT) && h.addFreeze(amount)) {
				petrified++;
				sink.petrified(h);
			}
		}
	}

	/** 원뿔(꼭짓점, 축, 반각, 길이) 과 구(중심, 반지름) 교차. */
	static boolean inCone(Vector3f p, Vector3f apex, Vector3f dir, float halfAng, float len, float r) {
		Vector3f d = new Vector3f(p).sub(apex);
		float along = d.dot(dir);
		if (along <= 0 || d.lengthSquared() > (len + r) * (len + r)) {
			return false;
		}
		float perp = (float) Math.sqrt(Math.max(0, d.lengthSquared() - along * along));
		// 축에서 원뿔 면까지의 수직 거리 ≤ r 이면 교차
		float dist = (float) (perp * Math.cos(halfAng) - along * Math.sin(halfAng));
		return dist <= r;
	}
}
