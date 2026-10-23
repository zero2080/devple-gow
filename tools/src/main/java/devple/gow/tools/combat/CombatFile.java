package devple.gow.tools.combat;

import devple.gow.tools.wad.WadFile;
import devple.gow.tools.wad.WadTag;
import devple.gow.tools.wad.WadTagType;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 전투 기술 테이블 {@code TWK_CombatFile_NNN} (WAD 태그 0x72 TWEAK_REF) 파서. 분석 근거는 docs/10-combat-system.md.
 * <pre>
 * 태그 데이터: u32 1, cstr "/TweakTemplates/CombatFile/NNN/", 이후 블록(아래 오프셋은 블록 기준)
 * 0x00 u16 moveCount, u16 actionCount, u16 actionTableOff(+4 기준), u16 (같은 값, 미사용)
 * 0x08 Move[moveCount]  0x18 바이트, id 오름차순 (sub_00209f58: 이진/선형 탐색)
 * 4+actionTableOff  {u16 relOff, u16 id}[actionCount]  → 루트 Branch (자기 위치 기준 상대 오프셋)
 * 각 목록 항목은 {u16 relOff(항목 자신 기준), u16 index} 4바이트.
 * </pre>
 */
public final class CombatFile {

	/** 블록 이름(TWK_CombatFile_NNN)과 경로. */
	private final String name;
	private final String path;
	private final List<Move> moves;
	private final List<Branch> actions;

	private CombatFile(String name, String path, List<Move> moves, List<Branch> actions) {
		this.name = name;
		this.path = path;
		this.moves = moves;
		this.actions = actions;
	}

	/**
	 * 기술(Move) 0x18 바이트.
	 *
	 * @param animHash   +0x00 애니 이름의 Hash127Upper
	 * @param id         +0x04 기술 ID (전 파일 공통 번호 공간: 000=0~49, 001=100~, 002=200~ …)
	 * @param speed      +0x0C half float, 애니 재생 속도 (sub_001f6fc8 인자)
	 * @param flags      +0x0E 0x2/0x4 = 피격자 경직 무시하고 반응 강제(0x2 는 방어 불가도 겸함), 0x8/0x20/0x40 = 타깃/카메라 처리
	 * @param poise      +0x10 s16 경직 한계: 이 기술 중 누적 피해가 이 값 이하면 피격 반응 없음, -1 = 슈퍼아머
	 * @param stateMask  +0x12 하위 바이트 (1/3/7, 의미 추정: 허용 상태)
	 * @param guard      +0x13 1 = 방어 자세(BLOCK/PARRY), 2 = 피격 경직 중
	 */
	public record Move(int offset, int animHash, int id, float speed, int flags, int poise, int stateMask, int guard,
					   List<Branch> branches, List<Attack> attacks, List<Event> events) {
	}

	/**
	 * 분기(Branch) 0x24 바이트 — 입력/이벤트 조건을 만족하면 target 기술로 전환.
	 *
	 * @param flags      +0x00 {@link #FLAG_IMMEDIATE} 등
	 * @param winStart   +0x04 u16/65535 현재 애니 진행률 하한
	 * @param target     +0x06 s16 다음 기술 ID (-1 없음, -2 = 다른 분기 차단용 더미)
	 * @param winEnd     +0x08 u16/65535 상한 (버퍼 입력은 이 시점에 실행)
	 * @param startPhase +0x0A u16/65535 다음 애니 시작 위치
	 * @param argMin     +0x0C s16 상대 HP 하한 (입력 폴링: 락온 타깃 HP, 이벤트: 공격자 HP. 기본 0)
	 * @param argMax     +0x0E s16 상대 HP 상한 (기본 0x7fff)
	 * @param hpMin      +0x10 s16 자신 HP 하한 (기본 0)
	 * @param hpMax      +0x12 s16 자신 HP 상한 (기본 0x7fff)
	 * @param classId    +0x14 s16 자신의 종류 ID(AI1 템플릿 번호) 일치 (-1 무시)
	 * @param matchA     +0x16 s16 상대 종류 ID (AI1 번호, 예 250 = 히드라 보스) (-1 무시)
	 * @param matchB     +0x18 s16 상대 자세 태그(상대 combatant+0x1b0, 이벤트 20 이 설정) (-1 무시, flags 0x4000 특수)
	 * @param matchC     +0x1A s16 (항상 -1 로 호출됨)
	 * @param input      +0x1C s8 입력/이벤트 코드 {@link #inputName(int)}
	 * @param mode       +0x1D s8 1 누름 2 뗌 3 시작부터 누르고 있음 4 누르고 있음 5 안 누름
	 * @param dir        +0x1E s8 스틱 방향 조건 {@link #dirName(int)}
	 * @param magic      +0x1F s8 마법 ID (선택 마법과 일치 + MP 필요, 7 = 특수 플래그)
	 * @param reaction   +0x20 s8 피격 반응 종류 일치 (-1 무시)
	 * @param level      +0x21 s8 필요 무기(블레이드) 레벨
	 */
	public record Branch(int offset, int index, int flags, float winStart, int target, float winEnd, float startPhase,
						 int argMin, int argMax, int hpMin, int hpMax, int classId, int matchA, int matchB, int matchC,
						 int input, int mode, int dir, int magic, int reaction, int level) {

		public boolean immediate() {
			return (flags & FLAG_IMMEDIATE) != 0;
		}
	}

	/**
	 * 공격 판정 구간(Attack) 0x20 바이트.
	 *
	 * @param impulse  +0x00 half[9]: 3개의 (x, y, z) 충격 벡터 — 지상 피격 / 공중 피격 / 방어 시 밀어내기 (순서 추정)
	 * @param start    +0x12 u16/65535 판정 시작
	 * @param end      +0x14 u16/65535 판정 끝
	 * @param mfx      +0x16 s16 MFX 템플릿 (재질별 타격 이펙트·사운드, flags 0x2 일 때)
	 * @param part     +0x18 s8 판정 부위(무기/히트박스) 인덱스
	 * @param damage   +0x19 s8 기본 피해
	 * @param flags    +0x1A 0x1 = 락온 타깃만, 0x2 = MFX 사용, 0x4 = 방향 보정, 0x8 = 특수(방어 관통 계열)
	 * @param blockReaction +0x1B 막혔을 때 피격자 이벤트 16 인자
	 * @param reaction +0x1C 피격 반응 종류 (이벤트 13/21 인자 → 피격자 분기 +0x20 과 대조)
	 * @param death    +0x1D 이 공격으로 죽었을 때 사망 종류 (이벤트 14 인자)
	 */
	public record Attack(int offset, int index, float[] impulse, float start, float end, int mfx, int part, int damage,
						 int flags, int blockReaction, int reaction, int death) {
	}

	/**
	 * 애니 이벤트(Event) 0x14 바이트.
	 *
	 * @param hash  +0x00 이름 해시 (사운드/조인트/스크립트)
	 * @param a     +0x04 half 파라미터 1
	 * @param b     +0x06 half 파라미터 2
	 * @param start +0x08, end +0x0A 발동 구간
	 * @param index +0x0C s16 템플릿 인덱스
	 * @param prob  +0x0E 발동 확률(%)
	 * @param type  +0x0F 종류 {@link #eventName(int)}
	 * @param cond  +0x10 조건 0 항상(1회) 1 내 공격 적중 프레임 2 적중(1회) 3 처치 4 플레이어에게 적중 5 막힘 6 사망
	 * @param level +0x11 필요 무기 레벨, flags +0x12 (0x1 레벨별 템플릿, 0x2 거리 조건)
	 */
	public record Event(int offset, int bit, int hash, float a, float b, float start, float end, int index, int prob,
						int type, int cond, int level, int flags) {
	}

	// ---- Branch.flags ----
	/** 버퍼 없이 즉시 전환(캔슬). 없으면 winEnd 시점까지 입력을 저장했다가 실행. */
	public static final int FLAG_IMMEDIATE = 0x1;
	public static final int FLAG_GROUND = 0x2;
	public static final int FLAG_AIR = 0x4;

	public static CombatFile parse(String name, String path, ByteBuffer src) {
		ByteBuffer b = src.duplicate().order(ByteOrder.LITTLE_ENDIAN);
		int nMoves = u16(b, 0), nActions = u16(b, 2), actOff = u16(b, 4);
		List<Move> moves = new ArrayList<>(nMoves);
		for (int i = 0; i < nMoves; i++) {
			moves.add(move(b, 8 + i * 0x18));
		}
		List<Branch> actions = new ArrayList<>(nActions);
		for (int i = 0; i < nActions; i++) {
			int e = 4 + actOff + i * 4;
			actions.add(branch(b, e + u16(b, e), u16(b, e + 2)));
		}
		return new CombatFile(name, path, moves, actions);
	}

	/** WAD 의 TWK_CombatFile_* 태그 전부. */
	public static List<CombatFile> fromWad(WadFile wad) {
		List<CombatFile> out = new ArrayList<>();
		for (WadTag t : wad.tags()) {
			if (t.tag() == WadTagType.TWEAK_REF && t.hasData() && t.name().startsWith("TWK_CombatFile")) {
				ByteBuffer d = t.data();
				int p = 4;
				while (d.get(p) != 0) {
					p++;
				}
				byte[] s = new byte[p - 4];
				d.get(4, s);
				out.add(parse(t.name(), new String(s, StandardCharsets.ISO_8859_1), d.slice(p + 1, d.limit() - p - 1)));
			}
		}
		return out;
	}

	private static Move move(ByteBuffer b, int o) {
		int nB = b.get(o + 0x14) & 0xFF, nA = b.get(o + 0x15) & 0xFF, nE = b.get(o + 0x16) & 0xFF;
		List<Branch> br = new ArrayList<>(nB);
		List<Attack> at = new ArrayList<>(nA);
		List<Event> ev = new ArrayList<>(nE);
		int base = o + u16(b, o + 6);
		for (int i = 0; i < nB; i++) {
			int e = base + i * 4;
			br.add(branch(b, e + u16(b, e), u16(b, e + 2)));
		}
		base = o + u16(b, o + 8);
		for (int i = 0; i < nA; i++) {
			int e = base + i * 4;
			at.add(attack(b, e + u16(b, e), u16(b, e + 2)));
		}
		base = o + u16(b, o + 0xA);
		for (int i = 0; i < nE; i++) {
			int e = base + i * 4;
			ev.add(event(b, e + u16(b, e), u16(b, e + 2)));
		}
		int x12 = u16(b, o + 0x12);
		return new Move(o, b.getInt(o), u16(b, o + 4), half(u16(b, o + 0xC)), u16(b, o + 0xE), b.getShort(o + 0x10),
				x12 & 0xFF, x12 >> 8, br, at, ev);
	}

	private static Branch branch(ByteBuffer b, int o, int index) {
		return new Branch(o, index, b.getInt(o), frac(b, o + 4), b.getShort(o + 6), frac(b, o + 8), frac(b, o + 0xA),
				b.getShort(o + 0xC), b.getShort(o + 0xE), b.getShort(o + 0x10), b.getShort(o + 0x12),
				b.getShort(o + 0x14), b.getShort(o + 0x16), b.getShort(o + 0x18), b.getShort(o + 0x1A),
				b.get(o + 0x1C), b.get(o + 0x1D), b.get(o + 0x1E), b.get(o + 0x1F), b.get(o + 0x20), b.get(o + 0x21));
	}

	private static Attack attack(ByteBuffer b, int o, int index) {
		float[] imp = new float[9];
		for (int i = 0; i < 9; i++) {
			imp[i] = half(u16(b, o + i * 2));
		}
		return new Attack(o, index, imp, frac(b, o + 0x12), frac(b, o + 0x14), b.getShort(o + 0x16), b.get(o + 0x18),
				b.get(o + 0x19), b.get(o + 0x1A) & 0xFF, b.get(o + 0x1B), b.get(o + 0x1C), b.get(o + 0x1D));
	}

	private static Event event(ByteBuffer b, int o, int bit) {
		return new Event(o, bit, b.getInt(o), half(u16(b, o + 4)), half(u16(b, o + 6)), frac(b, o + 8), frac(b, o + 0xA),
				b.getShort(o + 0xC), b.get(o + 0xE), b.get(o + 0xF), b.get(o + 0x10), b.get(o + 0x11), b.get(o + 0x12) & 0xFF);
	}

	/** 실행 파일의 half→float 변환 (0x209700: ((h&0x7fff)<<13 | (h&0x8000)<<16) * 2^112). */
	public static float half(int h) {
		int bits = ((h & 0x7FFF) << 13) | ((h & 0x8000) << 16);
		return Float.intBitsToFloat(bits) * 0x1p112f;
	}

	private static float frac(ByteBuffer b, int o) {
		return u16(b, o) / 65535f;
	}

	private static int u16(ByteBuffer b, int o) {
		return Short.toUnsignedInt(b.getShort(o));
	}

	// ---- 이름표 ----

	/**
	 * 분기 +0x1C 코드. 1~10 은 패드 비트(테이블 0x2c2238), 11 이상은 폴링 점프 테이블 0x2b9290 / 이벤트 코드(sub_002090b8 인자).
	 * 13 피격 반응, 14 사망, 16 공격을 막아냄(피격자), 17 내 공격 적중, 22 내 공격이 막힘. 0/15 는 폴링 불가(ID 요청 전용).
	 */
	public static String inputName(int code) {
		return switch (code) {
			case 1 -> "X";
			case 2 -> "SQUARE";
			case 3 -> "TRIANGLE";
			case 4 -> "CIRCLE";
			case 5 -> "L1";
			case 6 -> "MAGIC(bit0)";
			case 7 -> "L3";
			case 8 -> "R1";
			case 9 -> "R2";
			case 10 -> "R3";
			case 11 -> "AUTO";
			case 12 -> "ANY_INPUT";
			case 13 -> "EV_HIT_REACT";
			case 14 -> "EV_DEATH";
			case 16 -> "EV_BLOCKED_HIT";
			case 17 -> "HIT_CONFIRM";
			case 18 -> "SYNC";
			case 19 -> "STICK";
			case 20 -> "LANDED";
			case 21 -> "EV_STUN";
			case 22 -> "WAS_BLOCKED";
			case 0, 15 -> "REQUEST" + code;
			default -> "code" + code;
		};
	}

	/** 패드 비트 인덱스 (libpad2 버튼 워드, 0x17c370: (b0<<8|b1)^0xffff). */
	public static int padBit(int code) {
		return switch (code) {
			case 1 -> 6;
			case 2 -> 7;
			case 3 -> 4;
			case 4 -> 5;
			case 5 -> 2;
			case 6 -> 0;
			case 7 -> 9;
			case 8 -> 3;
			case 9 -> 1;
			case 10 -> 10;
			default -> -1;
		};
	}

	public static String modeName(int mode) {
		return switch (mode) {
			case 1 -> "press";
			case 2 -> "release";
			case 3 -> "hold-from-start";
			case 4 -> "hold";
			case 5 -> "not-held";
			default -> "m" + mode;
		};
	}

	/** 분기 +0x1E (점프 테이블 0x2b92f0). 9~14 는 오른쪽 스틱판(옵션 0x29f16c+4 == 1 일 때) 1~6. */
	public static String dirName(int d) {
		return switch (d) {
			case 0 -> "";
			case 1 -> "LS-neutral";
			case 2 -> "LS-any";
			case 3 -> "LS-left";
			case 4 -> "LS-right";
			case 5 -> "LS-fwd";
			case 6 -> "LS-back";
			case 7 -> "fall>2";
			case 8 -> "fall>4";
			case 9 -> "RS-neutral";
			case 10 -> "RS-any";
			case 11 -> "RS-left";
			case 12 -> "RS-right";
			case 13 -> "RS-fwd";
			case 14 -> "RS-back";
			case 15 -> "fall>15";
			default -> "dir" + d;
		};
	}

	/** 마법 ID (선택 마법 0x3044be). */
	public static String magicName(int m) {
		return switch (m) {
			case 0 -> "";
			case 3 -> "Lightning(Poseidon)";
			case 4 -> "MedusaHead";
			case 5 -> "Thunderbolt(Zeus)";
			case 6 -> "RaiseTheDead(Hades)";
			case 7 -> "BerserkOwned";
			default -> "magic" + m;
		};
	}

	/** 이벤트 +0x0F (점프 테이블 0x2b91b0, sub_00206030). */
	public static String eventName(int t) {
		return switch (t) {
			case 1 -> "TARGET_FX";
			case 2 -> "ORB_EMIT"; // OrbEmitter_Emit 0x2258D8 (idx = 적 WAD OrbEmitter 템플릿, 해골 머리치기 503 "SKS Decap")
			case 3 -> "SOUND";
			case 4 -> "MSG_3FE";
			case 5 -> "CAMERA_SHAKE";
			case 6 -> "FORCE_FEEDBACK";
			case 7 -> "GLOBAL_TIME";
			case 8 -> "SCRIPT";
			case 9 -> "INVULNERABLE";
			case 10 -> "SELF_HP";
			case 11 -> "CAMERA_FRAME"; // 0x2069F8 → 0x337C24 → 카메라 관리자 +0x7A4 (대상 프레이밍 줌)
			case 12 -> "NO_BLOCK";
			case 13 -> "ATTACH";
			case 14 -> "ANIM_SPEED";
			case 15 -> "FLAG_1";
			case 16 -> "FLASH_UI";
			case 17 -> "TRAIL_LEFT";
			case 18 -> "TRAIL_RIGHT";
			case 19 -> "FLAG_4";
			case 20 -> "SET_1B0";
			case 21 -> "GOD_METER_ADD";
			case 22 -> "FLAG_24";
			case 23 -> "CAMERA";
			case 24 -> "HIT_PAUSE";
			case 25 -> "HIT_COUNT";
			case 26 -> "CIRCLE_OFF";
			case 27 -> "CIRCLE_ON";
			case 28 -> "PLAYER_FLAG_4";
			case 29 -> "TARGET_SOUND";
			case 30 -> "FLAG_80";
			case 31 -> "SET_28C";
			case 32 -> "SET_358";
			case 33 -> "CHARGE_CHECK";
			case 34 -> "OWNER_FLAG";
			case 35 -> "SET_1B0_64";
			case 36 -> "SET_1B0_68";
			case 37 -> "CALL_1C4FF8";
			case 38 -> "FLAG_200";
			case 39 -> "BONUS";
			case 40 -> "SCR_SOUND";
			case 41, 42, 43, 44 -> "ATTACH_ON" + (t - 41);
			case 45, 46, 47 -> "ATTACH_OFF" + (t - 41);
			case 48 -> "CONCUSSION";
			default -> "ev" + t;
		};
	}

	public static String condName(int c) {
		return switch (c) {
			case 0 -> "";
			case 1 -> "on-hit";
			case 2 -> "on-hit-once";
			case 3 -> "on-kill";
			case 4 -> "on-hit-player";
			case 5 -> "on-blocked";
			case 6 -> "on-dead";
			default -> "cond" + c;
		};
	}

	public String name() {
		return name;
	}

	public String path() {
		return path;
	}

	public List<Move> moves() {
		return moves;
	}

	/** 루트 액션(이름 있는 진입 분기). AI/스크립트가 ID 로 요청(sub_00209378)하고, 대기 상태에서는 전역 분기로도 평가된다. */
	public List<Branch> actions() {
		return actions;
	}
}
