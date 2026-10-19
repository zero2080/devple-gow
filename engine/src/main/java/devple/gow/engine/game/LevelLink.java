package devple.gow.engine.game;

import devple.gow.engine.script.ScriptListener;

import java.util.Locale;
import java.util.function.Predicate;

/**
 * 레벨 슬롯 2개 모델 (원작 0x336B00[0..1], GameMgr 메시지 3..6 → 36 문서 §2). GL 비의존 — GameScene 과 순회 검증이 같이 쓴다.
 * <pre>
 * LoadWad(w)      0x1B0404: 현재가 아닌 슬롯에 w 로드 (Warp 준비 해제 0x29D914 = 0)
 * LoadForWarp(w)  0x1B0584: 같은 슬롯에 로드 + Warp 준비 (0x29D914 = 1)
 * Warp()          0x1B0688 → 0x1B2CE0: 준비됐으면 다른 슬롯이 현재가 되고 Start(모드 2, 슬롯 0 = 떠나온 레벨) 로 이동
 * LoadCheck(w)    0x1BCA10: w 가 로드된 슬롯이면 현재 레벨 = w (0x29DAE8). 위치는 그대로 — 이웃 레벨은 월드 좌표를 공유한다
 * </pre>
 * LoadWad/LoadForWarp 는 호출한 엔티티의 레벨 기준이다 (그 레벨이 아닌 슬롯을 교체). 두 레벨의 스크립트·충돌이 동시에 살아 있어서
 * SEWR01 → SEWR02 처럼 LoadCheck 없이 이웃 레벨로 걸어 들어가는 연결도 있다.
 * 이 재구현은 레벨을 하나만 올리므로 LoadCheck 전환 = 같은 월드 위치로 새 GameScene (seamless), 다른 슬롯 레벨 바닥만 밟으면
 * 같은 방식의 WALK 전환, Warp = 도착 Start 로 새 GameScene.
 */
public final class LevelLink {

	/**
	 * 전환 종류: SEAMLESS(LoadCheck, 위치 유지), WALK(다른 슬롯 레벨의 바닥으로 걸어 들어감, 위치 유지),
	 * WARP(Warp, 도착 Start), LOAD(Load(wad, entry), 진입점 이름).
	 */
	public enum Kind { SEAMLESS, WALK, WARP, LOAD }

	/** 결정된 전환. entry 는 LOAD 의 진입점 이름 (그 밖에는 null). */
	public record Transition(Kind kind, String from, String target, String entry) {
	}

	private final String current;
	private final Predicate<String> exists;
	private String other;
	private boolean warpReady;

	/**
	 * @param current 현재 레벨 (대문자 WAD 이름)
	 * @param other   다른 슬롯에 이미 있는 레벨 (이음 전환으로 왔으면 떠나온 레벨, 없으면 null)
	 * @param exists  WAD 존재 여부 (대문자 이름)
	 */
	public LevelLink(String current, String other, Predicate<String> exists) {
		this.current = norm(current);
		this.other = norm(other);
		this.exists = exists;
	}

	private static String norm(String w) {
		return w == null ? null : w.toUpperCase(Locale.ROOT);
	}

	public String current() {
		return current;
	}

	/** 다른 슬롯의 레벨 (없으면 null). */
	public String other() {
		return other;
	}

	public boolean warpReady() {
		return warpReady;
	}

	/** 스크립트 레벨 요청 → 전환 (없으면 null). 존재하지 않는 WAD 는 슬롯에 올리지 않는다 (원작 로드 실패). */
	public Transition handle(ScriptListener.LevelRequest r) {
		String w = norm(r.wad());
		switch (r.op()) {
			case LOAD_WAD, LOAD_FOR_WARP -> {
				if (w != null && !w.equals(current)) {
					other = exists.test(w) ? w : null;
				}
				warpReady = r.op() == ScriptListener.LevelOp.LOAD_FOR_WARP && other != null;
				return null;
			}
			case WARP -> {
				if (!warpReady || other == null) {
					return null;
				}
				warpReady = false;
				return new Transition(Kind.WARP, current, other, null);
			}
			case LOAD_CHECK -> {
				return w != null && w.equals(other) ? new Transition(Kind.SEAMLESS, current, other, null) : null;
			}
			case LOAD -> {
				return w != null && exists.test(w) ? new Transition(Kind.LOAD, current, w, r.entry()) : null;
			}
			default -> {
				return null;
			}
		}
	}

	/** LoadCheck 반환값: 그 WAD 가 슬롯에 있는지. */
	public boolean loaded(String wad) {
		String w = norm(wad);
		return w != null && (w.equals(current) || w.equals(other));
	}
}
