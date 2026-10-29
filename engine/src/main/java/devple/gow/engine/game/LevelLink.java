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
 * 적재가 끝나면 콜백 0x1B2AA8 (슬롯 +0x64 = 위 인자): LoadWad 면 0x1BC2E0 이 그 레벨 루트 컨텍스트를 켠다 (0x1BEEB8), LoadForWarp 면 Warp 준비만 —
 * <b>LoadForWarp 로 새로 올린 레벨은 꺼진 채</b> (스크립트·충돌·CMV 없음) Warp 0x1BC3A0 에서야 켜진다 ({@link #otherActive()}) [확실: 분기].
 * LoadWad/LoadForWarp 는 호출한 엔티티의 레벨 기준이다 (그 레벨이 아닌 슬롯을 교체). 두 레벨의 스크립트·충돌이 동시에 살아 있어서
 * SEWR01 → SEWR02 처럼 LoadCheck 없이 이웃 레벨로 걸어 들어가는 연결도 있다.
 * GameScene 은 LoadCheck 전환 (SEAMLESS) 과 다른 슬롯 레벨 바닥만 밟는 WALK 전환을 같은 씬 안에서 현재 ↔ 다른 슬롯 역할을 바꿔 처리하고
 * (GameScene.seamSwap, 43 §27 — 보스·덧붙는 레벨이 끼면 같은 월드 위치로 새 GameScene), Warp = 도착 Start 로 새 GameScene.
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
	private boolean otherActive;

	/**
	 * @param current 현재 레벨 (대문자 WAD 이름)
	 * @param other   다른 슬롯에 이미 있는 레벨 (이음 전환으로 왔으면 떠나온 레벨, 없으면 null)
	 * @param exists  WAD 존재 여부 (대문자 이름)
	 */
	public LevelLink(String current, String other, Predicate<String> exists) {
		this.current = norm(current);
		this.other = norm(other);
		this.otherActive = this.other != null;
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

	/**
	 * 다른 슬롯 레벨이 켜져 있는지 (스크립트 루프·충돌·CMV 질의 대상). LoadWad·이음 도착 = 켜짐, LoadForWarp 로 새로 올린 레벨 = 꺼짐.
	 * 이미 슬롯에 있는 레벨을 다시 부르면 0x1BC110 이 다시 읽지 않으므로 LoadForWarp 는 상태를 그대로 두고, LoadWad 는 켠다 [추정: 이미 올라 있는
	 * 레벨에 0x1B2AA8 이 다시 불리는지는 미추적].
	 */
	public boolean otherActive() {
		return other != null && otherActive;
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
					boolean same = w.equals(other);
					other = exists.test(w) ? w : null;
					if (r.op() == ScriptListener.LevelOp.LOAD_WAD) {
						otherActive = true;
					} else if (!same) {
						otherActive = false; // LoadForWarp: 새로 올린 레벨은 Warp 전까지 꺼짐
					}
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

	/**
	 * "Levels" 힙 크기: Heap_SetupLevels 0x1BB5F0 이 루트 힙에서 0x1000300 바이트를 떼어 "Levels" 힙 (0x29DAF4) 을 만든다 — 두 레벨 슬롯은 그 안에
	 * WAD HEADER (0x378) 값만큼 (Tag_Header 0x185760 → 0x186590: 4 정렬, 가장 큰 빈 블록보다 크면 그만큼) 하위 힙을 받고, 0x29D4C8 에 따라 힙의 위/아래
	 * 끝에서 잡는다 (0x13DE68 / 0x13DCB8). Hero · Slots · Upgrade 힙은 루트 힙의 따로 블록 (0x1BB510) [확실].
	 */
	public static final int LEVELS_HEAP = 0x1000300;
	/** 동영상 준비 0x1B3490 이 요구하는 "Levels" 힙의 가장 큰 빈 블록: 0x1B350C–0x1B3518 {@code slt 0x322417, 빈 블록} 이 거짓이면 다른 슬롯을 비운다 [확실]. */
	public static final int MOVIE_MIN_FREE = 0x322417;

	/**
	 * PlayMovie / PreloadMovie (0x1B3440 · 0x1AF848 → 0x1B3490) 가 다른 슬롯을 비우는지: 미리 읽어 둔 같은 이름이 아니면 0x13E238("Levels" 힙, 8) 로
	 * 가장 큰 빈 블록을 재고 0x322417 이하면 영웅이 있지 않은 슬롯 (0x1B3520–0x1B358C: 영웅 문맥 +0x88 과 슬롯 +0x5C 비교) 을 0x1BC940(1 − 영웅 슬롯, 3)
	 * → 0x1BBA20(1, 슬롯, 3) 으로 내린다 (슬롯 +0x68 & 1 = 올라 있을 때만) [확실]. 엔진은 빈 블록 = Levels 힙 − 두 슬롯 HEADER 값 [근사: 블록 머리 ·
	 * 미리 읽은 동영상 버퍼 무시] — PAND00C (7 MB) + PAND00D (8 MB) = 빈 1 MB → 눈 방 마지막 빛줄기 동영상 p00MP03 에서 PAND00C 가 내려가고
	 * FakeEyeDoor-ReloadWadC 가 다시 읽어 EyeDoor-EvtLoaded (202) · 205 (엘리베이터) 가 온다 (43 §49). PAND00B (9) + PAND00E (2) = 빈 5 MB → 그대로
	 * (죽어 가는 병사 p00MP02 의 이웃 501, 43 §32).
	 *
	 * @param heapCurrent 영웅 슬롯 레벨의 HEADER 값
	 * @param heapOther   다른 슬롯 레벨의 HEADER 값 (없으면 0)
	 */
	public static boolean movieDropsOther(int heapCurrent, int heapOther) {
		return heapOther > 0 && (long) LEVELS_HEAP - align4(heapCurrent) - align4(heapOther) <= MOVIE_MIN_FREE;
	}

	private static long align4(int v) {
		return ((long) v + 3) & ~3L;
	}

	/** WAD 첫 태그 HEADER (0x378) 의 값 = 레벨 힙 크기 (R_PERMK 는 −1, 없으면 0). 파일 앞 36 바이트만 읽는다. */
	public static int levelHeap(java.nio.file.Path wad) {
		try (java.io.InputStream in = java.nio.file.Files.newInputStream(wad)) {
			byte[] b = in.readNBytes(36);
			if (b.length < 36) {
				return 0;
			}
			java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN);
			return Short.toUnsignedInt(bb.getShort(0)) == devple.gow.tools.wad.WadTagType.HEADER ? bb.getInt(32) : 0;
		} catch (java.io.IOException | RuntimeException e) {
			return 0;
		}
	}

	/** 동영상 준비가 다른 슬롯을 내렸다 (0x1BBA20(1, 슬롯, 3)): 같은 레벨을 다시 LoadWad 하면 새로 읽는다. */
	public void dropOther() {
		other = null;
		otherActive = false;
		warpReady = false;
	}

	/** LoadCheck 반환값: 그 WAD 가 슬롯에 있는지. */
	public boolean loaded(String wad) {
		String w = norm(wad);
		return w != null && (w.equals(current) || w.equals(other));
	}
}
