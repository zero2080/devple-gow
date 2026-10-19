package devple.gow.engine.game;

import devple.gow.engine.script.ScriptEntity;
import devple.gow.engine.script.ScriptWorld;
import devple.gow.tools.level.GoInstance;
import devple.gow.tools.level.LevelData;

import java.util.Locale;

/**
 * 레벨에 들어올 때의 시작 지점 (GL 비의존, GameScene 과 순회 검증 공용, 36 문서 §2).
 * <pre>
 * Warp 도착  : Start 모드 2 중 슬롯 0 = 떠나온 레벨 (원작 0x1AE710), 없으면 기본 Start
 * 진입점 이름: 같은 이름의 GO 인스턴스/오브젝트, 없으면 같은 이름의 스크립트 엔티티 (Load(wad, entry) / --entry)
 * 기본       : 첫 모드 1 Start 엔티티 (원작 플레이어 생성 위치) → start* GO → 원점
 * </pre>
 * 방향 = 행렬의 Y 회전 (GoInstance rot[1] 과 같은 규약: m[8] = sin, m[10] = cos).
 */
public final class LevelEntry {

	/** 시작 지점: 위치, 방향, 도착 카메라 이름(Start 슬롯 2, 없으면 ""), 출처 설명. */
	public record Spot(float[] pos, float yaw, String camera, String source) {
	}

	private LevelEntry() {
	}

	/**
	 * @param entry    진입점 이름 (null 가능)
	 * @param warpFrom Warp 로 떠나온 레벨 (null 이면 Warp 도착 아님)
	 */
	public static Spot resolve(LevelData data, ScriptWorld world, String entry, String warpFrom) {
		if (warpFrom != null) {
			ScriptEntity t = world.warpTarget(warpFrom);
			if (t != null) {
				return of(world, t, t.mode() == ScriptWorld.START_WARP ? "Warp 도착" : "Warp 기본");
			}
		}
		if (entry != null) {
			GoInstance g = data.layout.instances.stream().filter(i -> i.name().equalsIgnoreCase(entry) || i.object().equalsIgnoreCase(entry))
					.findFirst().orElse(null);
			if (g != null) {
				return new Spot(g.pos().clone(), g.rot()[1], "", "GO " + g.name());
			}
			ScriptEntity e = world.find(entry);
			if (e != null) {
				return of(world, e, "엔티티");
			}
		}
		ScriptEntity st = world.defaultStart();
		if (st != null) {
			return of(world, st, "Start");
		}
		GoInstance g = data.layout.instances.stream().filter(i -> i.object().toLowerCase(Locale.ROOT).startsWith("start")).findFirst()
				.orElse(null);
		if (g != null) {
			return new Spot(g.pos().clone(), g.rot()[1], "", "GO " + g.name());
		}
		return new Spot(new float[3], 0, "", "원점");
	}

	/** 바닥 탐색: 위 {@value #UP}, 아래 {@value #DROP} 유닛. */
	public static final float UP = 32, DROP = 600;
	/** 보정 때는 조금 위(계단·발판 위 Start)의 바닥도 본다. */
	static final float SNAP_UP = 160;

	/**
	 * 시작 지점 아래에 정적 충돌 바닥이 없으면 (엘리베이터·움직이는 발판 위 Start — 움직이는 충돌은 미구현) 수평 radius 안의
	 * 가장 가까운 바닥 위로 옮긴다 [근사]. 바닥이 있으면 그대로. 못 찾으면 null.
	 */
	public static float[] snapToFloor(devple.gow.engine.level.LevelCollision col, float[] p, float radius) {
		if (!Float.isNaN(col.groundHeight(p[0], p[1] + UP, p[2], DROP))) {
			return p;
		}
		for (int r = 0; r <= radius; r += 16) {
			for (int k = 0; k < (r == 0 ? 1 : 16); k++) {
				double a = 2 * Math.PI * k / 16;
				float x = p[0] + (float) (r * Math.cos(a)), z = p[2] + (float) (r * Math.sin(a));
				float g = col.groundHeight(x, p[1] + SNAP_UP, z, DROP + SNAP_UP);
				if (!Float.isNaN(g)) {
					return new float[]{x, g, z};
				}
			}
		}
		return null;
	}

	private static Spot of(ScriptWorld w, ScriptEntity e, String kind) {
		float[] m = e.world;
		String cam = e.type() == 9 ? String.valueOf(w.slot(e, "CameraName")) : "";
		return new Spot(e.position(), (float) Math.atan2(m[8], m[10]), cam, kind + " '" + e.name() + "'");
	}
}
