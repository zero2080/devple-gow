package devple.gow.engine.nav;

import devple.gow.engine.game.Tweaks;
import devple.gow.tools.level.BallHull;
import devple.gow.tools.level.GoBehavior;
import devple.gow.tools.level.LevelData;
import devple.gow.tools.level.LevelLayout;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * R1 상황 버튼으로 다루는 레벨 오브젝트 (38 문서 §3). 레벨의 GO 비헤이비어 ({@link GoBehavior}) 와 Creator "chest" 에서 만든다.
 * <table>
 * <tr><td>CSM</td><td>IO_CSM: R1 한 번에 붙어 영웅 ANICSM1 애니 동안 오브젝트 애니가 속도 1 로 진행 (쌍발차기 문, 선장실 문, 책).
 *   진행률 % 가 바뀔 때마다 과제 이벤트 1023 (인자 0 = %, 인자 1 = 과제 id) — 38 문서 §3.1</td></tr>
 * <tr><td>CHEST</td><td>상자 (Creator Name0 "chest", unk5 = CSM 템플릿, unk6 = 과제 id): chestGrab 동안 R1 을 누르고 있음 → chestOpen, 100 % 에 오브</td></tr>
 * <tr><td>PUSH</td><td>PushPull 상자류: 붙잡기 1048 / 놓기 1049 (인자 0 = id), 상자와 그 Marker(그룹 = Marker Id) 가 같이 움직인다</td></tr>
 * <tr><td>LEVER</td><td>PushPull 중 TWK Push Instance Name 이 Crank/Lever/Puller 인 것: 레버 = R1 한 번에 leverpull 끝까지,
 *   크랭크 = R1 을 누른 채 스틱으로 돌림 (Acc/Dec)</td></tr>
 * <tr><td>LIFT</td><td>IO_TandF 들어 올리는 문: R1 을 누를 때마다 속도 +Acc, TF Time 동안 안 누르면 −Dec</td></tr>
 * </table>
 */
public final class Interactables {

	public enum Kind { CSM, CHEST, PUSH, LEVER, LIFT }

	/** CSM 애니 이름 TWK 키 (해시): 영웅 동작 ANICSM1 / ANICSM2 / 오브젝트 동작 (0x21508C / 0x2150A8 / 0x2150C4). */
	public static final int CSM_HERO_ANIM = 0xb50a547c, CSM_HERO_ANIM2 = 0xb50a547d, CSM_OBJECT_ANIM = 0xfea292e6;
	/**
	 * CSM 템플릿 +0xB8 비트 0 (TWK 키 #fb908478, 0x215264): 켜져 있으면 ANICSM1 동안 R1 을 누르고 있어야 붙어 있다 (원작 0x1C4BDC) [확실].
	 * 상자류(chest/HydraHealth/Pinata/Shard) = 1, 문·책·뿔피리·선장 = 0. 값은 하위 바이트만 유효 (상위 바이트 쓰레기).
	 */
	public static final int CSM_HOLD_FLAG = 0xfb908478;
	/** CSM "Chest Type" (+0x9E), 종류 순환 주기 (+0xA4), 종류별 OrbEmitter (+0x90/+0x94/+0x98) — 생성자 0x215010 [확실: 해시·오프셋]. */
	public static final int CHEST_TYPE = 0x0c67f4f9, CHEST_CYCLE = 0x70f23994;
	static final int[] CHEST_ORBS = {0xb9a0d2ad, 0x4d7975bf, 0x4d7975c0};
	/** TAndF 영웅 애니 (ANITandF1 잡기 liftdoor1 / ANITandF2 들기 liftdoor2 / ANITandF3 끝) 키 해시 (0x2136D4/0x2136F0/0x21370C). */
	public static final int TANDF_GRAB_ANIM = 0xff5f5921, TANDF_LIFT_ANIM = 0xdc77f412, TANDF_END_ANIM = 0x4e2b8035;
	/** Push/TAndF 템플릿 속도 키: 최대 (+0x38 / +0x20), 최대 되감기 (+0x3C / +0x24), Push 되감기 가속 (+0x34). Acc/Dec 는 이름 키. */
	public static final int SPEED_MAX = 0xfe467fe6, SPEED_MAX_BACK = 0xb503fbe4, PUSH_REWIND_ACC = 0xb6ba95a3;
	/** Push 템플릿 +0x108 비트 4 (양방향 크랭크), +0x40 멈춤 자리 허용 (생성자 0x216370 / 기본값 0x216788) [확실]. */
	static final int PUSH_TWO_WAY = 0x228882E3, PUSH_DETENT_TOL = 0xA4C814E9;
	/**
	 * Push 템플릿 +0x108 비트 1 (#4ff66d39): 끝에 닿아도 크랭크 클래스 0x1000 을 끄지 않음 (0x212BB4), 비트 2 (#5529319f): 1015 리셋이 되감지 않고
	 * 시간 0 으로 바로 (0x212C34) [확실: 생성자 0x216644 / 0x216658 · 사용처]. 값은 하위 비트 (다른 비트 키와 같은 꼴).
	 */
	static final int PUSH_KEEP_AT_END = 0x4FF66D39, PUSH_RESET_SNAP = 0x5529319F;
	/** Push 템플릿 +0x28 상자 최대 속도 (#95b8bfb6, 0x216470) [확실]. */
	public static final int PUSH_SLIDE_MAX = 0x95B8BFB6;

	public static final class Item {
		public final Kind kind;
		public final String owner;
		public final Vector3f pos;
		public final int template;
		/** CSM/CHEST = 과제 id (1023 인자 1), PUSH/LEVER = 붙잡기 id (1048/1049 인자 0). */
		public final int id;
		public final String heroAnim, finishAnim, label;
		public final float radius;
		/** 진행률 0..1 (CSM/CHEST/LEVER) = {@link #task} 의 애니 위치 / 길이. */
		public float progress;
		public int lastPercent = -1;
		public boolean done;
		/** 스크립트 Vis 로 숨겨짐 (Visible 2) — 잡을 수 없다 (예 PAND00C 엘리베이터 레버는 고리 퍼즐 205 뒤 LeverOn). */
		public boolean hidden;
		/**
		 * "Show R2" (#ca686863, 값의 bit 0): 가까이 서면 R2 안내 (슬롯 0, 0x1DC3F8 → 0x21DA50(0, 14)), TAndF 를 잡은 동안 R2 연타 안내
		 * (슬롯 2, 0x1E17F0 → 0xB). 로더 PushPull 0x216738 (+0x108 &amp; 8) · TAndF 0x2138C0 (+0x158 &amp; 2) · CSM 0x215238 (+0xB8 &amp; 4).
		 */
		public boolean showPrompt;
		/**
		 * 손잡이 관절 handle1..8 (0x200A50 "handle%1d"): {x, y, z, 축 z 의 수평 x, z (정규화)}. 잡기 판정 (0x1C3918) 과 붙은 자리
		 * (0x1C4318: 영웅 = 손잡이 + 축·9.6, −축을 봄) 의 기준. 비어 있으면 중심 반경 판정 [근사].
		 */
		public final java.util.List<float[]> handles = new java.util.ArrayList<>();
		/**
		 * 상자 (CHEST): CSM "Chest Type" (#0c67f4f9, 템플릿 +0x9E s16 — 1 plain, 2 health, 3 magic, 4 power, 5 = 2→3→4 순환) 와
		 * 지금 보이는 종류 (+0x1E4, 0x215BB0 순환: 주기 #70f23994 +0xA4 초), 종류별 OrbEmitter 템플릿 (+0x90 #b9a0d2ad / +0x94 #4d7975bf / +0x98 #4d7975c0,
		 * 0x2161A8: 종류 2..5 면 (보이는 종류 − 2) mod 3 번째, 아니면 첫째) [확실: 코드].
		 */
		public int chestType, chestShown;
		public float chestCycle;
		public final int[] chestOrbs = {-1, -1, -1};

		/** 완료 때 방출할 OrbEmitter 템플릿 번호 (없으면 −1). */
		public int chestOrb() {
			int idx = chestType >= 2 && chestType <= 5 ? Math.floorMod(chestShown - 2, 3) : 0;
			return chestOrbs[idx];
		}
		/** 손잡이 판정 값 (0x213A38): 높이차 (#25bb317e ×16), 수평 거리 (#2b2d2457 ×16), cos(Handle Angle), 뒤 거리 (#b2a8856f ×16). */
		public float grabDy = 16, grabR = 32, grabCos = 0.3f, grabBack = 0;
		/** LIFT 오브젝트 애니 (0x213B28): 연타 중 = #d7f2bea8 (trapDoorTandF, 길이 × 위치), 완료 = #c5b0169f (trapOpen, 속도 1). openTime = 완료 뒤 경과. */
		public String liftObjectAnim, openObjectAnim;
		public float openTime;
		/** 오브젝트 애니 길이 (초): 과제 진행률의 분모 (원작 0x215928 f13). 애니를 못 찾으면 1 초 [근사]. */
		public float length = 1f;
		private TaskProgress task;
		/** 진행 속도 (붙음 / 떨어짐) — 검증 하네스가 붙잡은 채로 끝까지 돌릴 때 쓴다 (LIFT = TAndF Acc / −Dec, 그 밖은 1 / −4). */
		public float engagedSpeed = TaskProgress.ENGAGED_SPEED, rewindSpeed = TaskProgress.REWIND_SPEED;
		/** CSM/CHEST: ANICSM1 동안 R1 을 누르고 있어야 함 ({@link #CSM_HOLD_FLAG}). */
		public boolean hold;
		/** 오브젝트 애니 길이를 오브젝트 ANM 에서 읽었는지 (상자는 Creator 라 ANM 이 없다 → 영웅 ANICSM1 길이를 쓴다). */
		public boolean lengthKnown;
		/** LEVER 중 크랭크 (Push Type 1–4: R1 을 누른 채 스틱으로 돌림, 원작 0x212998). false = 레버 (Type 5/6, 한 번 당김, 0x2127B0). */
		public boolean crank;
		/** 크랭크/TAndF 속도 TWK: Acc, Dec, 최대, 최대 되감기, 되감기 가속 (Push #b6ba95a3), TF Time (TAndF). */
		public float acc = 1, dec = 1, maxSpeed = 1, maxBack = 2, rewindAcc = 2, tfTime = 0.25f;
		/** TAndF 잡기 애니 (ANITandF1). */
		public String startAnim;
		/** 오브젝트 애니 act (CSM #fea292e6, null = 가장 긴 act) — 진행 시간으로 오브젝트 관절 자세를 정할 때. */
		public String objectAnim;
		/**
		 * Push "Type" 하위 바이트 (−1 = PushPull 아님): 4 = 링 크랭크 (0x212998 모드 2) — 0·¼·½·¾ 회전에 멈춤 자리, 놓아도 되감지 않고
		 * 가장 가까운 멈춤 자리로 간다. twoWay = 템플릿 +0x108 비트 4 (#228882e3, 스틱을 반대로 밀면 역회전), detentTol = +0x40 (#a4c814e9, 기본 0.03).
		 */
		public int pushType = -1;
		/**
		 * 밀기 상자 (Push Type 0): 무게 등급 = 템플릿 +0x1A "Weight" (s16 = TWK 값 하위 16 비트, 0x216450 — 데이터가 float 0.5 의 비트에
		 * 0/1/2 를 얹어 둠: Pandora's Box 0x3F000002 → 2) → 영웅 밀기 속도·차기 속도 표 번호 (0x244FB0) [확실: 코드 / 추정: 하위 16 비트 읽기 = Type 과 같음].
		 * slideMax = #95b8bfb6 (+0x28, m/s) 상자 최대 속도 (0x211BC0), vel = 상자 속도 (월드 유닛/초).
		 */
		public int weight;
		public float slideMax = 5f;
		public final Vector3f vel = new Vector3f();
		public boolean twoWay;
		public float detentTol = 0.03f;
		/** Push +0x108 비트 1 / 2 ({@link #PUSH_KEEP_AT_END} / {@link #PUSH_RESET_SNAP}). */
		public boolean keepAtEnd, resetSnap;
		/**
		 * 스크립트 1015 리셋 중 (원작 PushPull +0x29A 비트 8, 0x2100E0): 크랭크류는 잡을 수 없이 되감기 가속으로 0 까지 → 0 에서 다시 잡힘 (0x212C08).
		 * 레버는 속도 −4 로 0 까지 (0x2101C8 → 0x2127B0).
		 */
		public boolean resetting;
		/**
		 * 대포 (Push Type 2): cannonKick = 끝까지 감김 → 영웅 발차기 중 (영웅 +0x2D8 0x20000, ANIStartKickBox), cannonFired = 영웅 애니가 슬롯 0x54 에
		 * 닿음 → 오브젝트 +0x29A 비트 2 (끝 시간을 그대로 두고 100 %).
		 */
		public boolean cannonKick, cannonFired;

		/** 대포 (Push Type 2 = 0x212998 모드 3: 끝에서 멈춰 영웅 발차기를 기다림). */
		public boolean cannon() {
			return crank && pushType == 2;
		}

		public TaskProgress.RingParams ringParams() {
			return new TaskProgress.RingParams(acc, dec, rewindAcc, maxSpeed, maxBack, detentTol, twoWay);
		}

		/** 과제 진행 상태 (CSM/CHEST = 과제 id, LEVER = PushPull id, LIFT = TAndF id 로 1023 을 보낸다). LIFT 는 위치 0..1. */
		public TaskProgress task() {
			if (task == null) {
				task = new TaskProgress(id, kind == Kind.LIFT ? 1 : length, engagedSpeed, rewindSpeed);
				task.stopAtEnd = kind == Kind.LEVER;
			}
			return task;
		}

		/** 과제가 시작됐는지 (진행 상태가 있는지). */
		public boolean taskStarted() {
			return task != null;
		}

		/** 오브젝트 애니 길이를 바꾸고 과제를 새로 만든다 (진행 전에만). */
		public void relength(float l) {
			length = l;
			task = null;
		}

		public TaskProgress.LiftParams liftParams() {
			return new TaskProgress.LiftParams(acc, dec, maxSpeed, maxBack, tfTime);
		}

		public Item(Kind kind, String owner, Vector3f pos, int template, int id, String heroAnim, String finishAnim, String label, float radius) {
			this.kind = kind;
			this.owner = owner;
			this.pos = pos;
			this.template = template;
			this.id = id;
			this.heroAnim = heroAnim;
			this.finishAnim = finishAnim;
			this.label = label;
			this.radius = radius;
		}

		@Override
		public String toString() {
			return kind + " " + owner + " (" + label + ", 템플릿 " + template + ", id " + id + ")";
		}
	}

	private final List<Item> items = new ArrayList<>();

	public List<Item> items() {
		return items;
	}

	public Item add(Item it) {
		items.add(it);
		return it;
	}

	/** 레벨 GO 비헤이비어로 만든다. tw = 레벨 TWK (CSM/Push 템플릿). */
	public static Interactables of(LevelData lv, Tweaks tw) {
		Interactables out = new Interactables();
		for (LevelLayout.Placement p : lv.layout.placements()) {
			GoBehavior b = lv.behaviors.get(p.instance().name());
			if (b == null) {
				continue;
			}
			float[] w = p.jointWorld()[0];
			Vector3f pos = new Vector3f(w[12], w[13], w[14]);
			float r = 24;
			String anm = null;
			for (String ref : p.object().refs()) {
				BallHull h = lv.hulls.get(ref);
				if (h != null && h.kind == 0) {
					r = Math.max(12, Math.min(64, h.sphere[3] * p.instance().scale()));
				}
				if (ref.startsWith("ANM_")) {
					anm = ref;
				}
			}
			int before = out.items.size();
			switch (b.type()) {
				case "IO_CSM" -> {
					String node = String.format("/TweakTemplates/CSM/%03d/", b.template());
					String anim = tw != null ? tw.sHash(node, CSM_HERO_ANIM, "doorKick") : "doorKick";
					String label = tw != null ? tw.s(node, "Instance Name", "CSM") : "CSM";
					Item it = new Item(Kind.CSM, p.instance().name(), pos, b.template(), b.id(), anim,
							tw != null ? tw.sHash(node, CSM_HERO_ANIM2, null) : null, label, r);
					it.objectAnim = tw != null ? tw.sHash(node, CSM_OBJECT_ANIM, null) : null;
					float len = animLength(lv, anm, it.objectAnim, -1);
					it.lengthKnown = len >= 0;
					it.length = len >= 0 ? len : it.length;
					it.hold = tw != null && (tw.rawHash(node, CSM_HOLD_FLAG).orElse(0) & 1) != 0;
					it.showPrompt = showR2(tw, node);
					out.items.add(it);
				}
				case "PushPull" -> {
					String node = String.format("/TweakTemplates/Push/%03d/", b.template());
					String label = tw != null ? tw.s(node, "Instance Name", "Push") : "Push";
					String l = label.toLowerCase(Locale.ROOT);
					// Push "Type" 하위 바이트 (+0x264): 0·7·8 = 상자 물리, 1–4 = 크랭크류 (1 크랭크, 2 대포 AthnGun/AthensHarpoon, 3 기중기, 4 링), 5 = 레버,
					// 6 = 물속 레버 — 갱신 0x212398 이 Type 으로만 고르고, 1015 리셋 0x2100E0 (표 0x2B9420) 도 1·2·4 를 같은 크랭크 되감기로 묶는다 [확실: 코드].
					// 예전 엔진은 이름 (Crank/Lever/Puller) 으로 골라 AthnGun·CoffinLid·HadesHead·Pand04CElevs 등 11 개를 밀기 상자로 다뤘다 (43 문서 §14)
					int type = tw != null && tw.raw(node, "Type").isPresent() ? tw.raw(node, "Type").getAsInt() & 0xff : -1;
					boolean lever = type >= 0 ? type >= 1 && type <= 6
							: l.contains("crank") || l.contains("lever") || l.contains("leever") || l.contains("puller");
					boolean crank = lever && (type >= 0 ? type <= 4 : l.contains("crank"));
					Item it = new Item(lever ? Kind.LEVER : Kind.PUSH, p.instance().name(), pos, b.template(), b.id(),
							lever ? (crank ? "crankIdle" : type == 6 ? "leverPullUW" : "leverpull") : "pushIdle", null, label, r);
					if (!lever && tw != null) {
						it.weight = tw.raw(node, "Weight").isPresent() ? tw.raw(node, "Weight").getAsInt() & 0xffff : 0;
						it.slideMax = Float.intBitsToFloat(tw.rawHash(node, PUSH_SLIDE_MAX).orElse(Float.floatToIntBits(5f)));
					}
					if (lever) {
						float len = animLength(lv, anm, null, -1);
						it.lengthKnown = len >= 0;
						it.length = len >= 0 ? len : it.length;
						it.crank = crank;
						it.pushType = type;
						if (tw != null) {
							it.keepAtEnd = (tw.rawHash(node, PUSH_KEEP_AT_END).orElse(0) & 1) != 0;
							it.resetSnap = (tw.rawHash(node, PUSH_RESET_SNAP).orElse(0) & 1) != 0;
							it.twoWay = tw.rawHash(node, PUSH_TWO_WAY).orElse(0) != 0;
							it.detentTol = Float.intBitsToFloat(tw.rawHash(node, PUSH_DETENT_TOL).orElse(Float.floatToIntBits(0.03f)));
							it.acc = tw.f(node, "Acc", 1f);
							it.dec = tw.f(node, "Dec", 1f);
							it.rewindAcc = Float.intBitsToFloat(tw.rawHash(node, PUSH_REWIND_ACC).orElse(Float.floatToIntBits(2f)));
							it.maxSpeed = Float.intBitsToFloat(tw.rawHash(node, SPEED_MAX).orElse(Float.floatToIntBits(1f)));
							it.maxBack = Float.intBitsToFloat(tw.rawHash(node, SPEED_MAX_BACK).orElse(Float.floatToIntBits(2f)));
						}
					}
					it.showPrompt = showR2(tw, node);
					out.items.add(it);
				}
				case "IO_TandF" -> {
					// 들어 올리는 문·함정문 (41 문서 §IO_TandF): R1 로 잡고 연타 — 연타 중 Acc, 멈추면 −Dec 로 내려온다 [추정: 속도 해석·입력]
					String node = String.format("/TweakTemplates/TAndF/%03d/", b.template());
					String label = tw != null ? tw.s(node, "Instance Name", "TAndF") : "TAndF";
					String lift = tw != null ? tw.sHash(node, TANDF_LIFT_ANIM, "liftdoor2") : "liftdoor2";
					Item it = new Item(Kind.LIFT, p.instance().name(), pos, b.template(), b.id(), lift,
							tw != null ? tw.sHash(node, TANDF_END_ANIM, null) : null, label, r);
					it.startAnim = tw != null ? tw.sHash(node, TANDF_GRAB_ANIM, null) : null;
					it.liftObjectAnim = tw != null ? tw.sHash(node, 0xd7f2bea8, null) : null;
					it.openObjectAnim = tw != null ? tw.sHash(node, 0xc5b0169f, null) : null;
					it.length = animLength(lv, anm, null, 0);
					if (it.length < 0.05f) {
						// 문 ANM 은 길이 0 (오브젝트 트랙만) → TAndF #fe467fe6 (1.5) 를 들어 올리는 시간으로 [추정]
						it.length = tw != null ? Math.max(0.1f, Float.intBitsToFloat(tw.rawHash(node, 0xfe467fe6).orElse(Float.floatToIntBits(1.5f)))) : 1.5f;
					}
					if (tw != null) {
						it.engagedSpeed = tw.f(node, "Acc", 1f);
						it.rewindSpeed = -tw.f(node, "Dec", 20f);
						it.acc = tw.f(node, "Acc", 1f);
						it.dec = tw.f(node, "Dec", 20f);
						it.tfTime = tw.f(node, "TF Time", 0.25f);
						it.maxSpeed = Float.intBitsToFloat(tw.rawHash(node, SPEED_MAX).orElse(Float.floatToIntBits(1.5f)));
						it.maxBack = Float.intBitsToFloat(tw.rawHash(node, SPEED_MAX_BACK).orElse(Float.floatToIntBits(20f)));
					}
					it.showPrompt = showR2(tw, node);
					out.items.add(it);
				}
				default -> {
					// Breakable: game/Breakables
				}
			}
			if (out.items.size() > before) {
				Item it = out.items.get(out.items.size() - 1);
				String node = switch (b.type()) {
					case "IO_CSM" -> String.format("/TweakTemplates/CSM/%03d/", b.template());
					case "PushPull" -> String.format("/TweakTemplates/Push/%03d/", b.template());
					default -> String.format("/TweakTemplates/TAndF/%03d/", b.template());
				};
				handles(it, p, tw, node);
			}
		}
		return out;
	}

	private static final int H_GRAB_DY = 0x25bb317e, H_GRAB_R = 0x2b2d2457, H_GRAB_BACK = 0xb2a8856f;

	/**
	 * 손잡이 관절 자리를 지금 관절 월드로 다시 (원작 0x1C3918 은 매 질의 관절 행렬을 읽는다): 부모 링크로 움직이는 오브젝트 — ATHN02C 발리스타
	 * 크랭크 gobiggun1crank 는 회전판 golazysuegroup "Anim" 관절의 자식이라 회전판이 돌면 손잡이도 돈다 [확실: GO 부모 링크 데이터].
	 */
	public static void refreshHandles(Item it, devple.gow.tools.anim.Skeleton sk, float[][] jw) {
		var joints = sk.joints;
		java.util.List<float[]> fresh = new java.util.ArrayList<>();
		for (int n = 1; n <= 8; n++) {
			String want = "handle" + n;
			for (int j = 0; j < joints.size() && j < jw.length; j++) {
				if (joints.get(j).name().equalsIgnoreCase(want)) {
					float[] m = jw[j];
					float zx = m[8], zz = m[10], l = (float) Math.hypot(zx, zz);
					if (l > 1e-4f) {
						fresh.add(new float[]{m[12], m[13], m[14], zx / l, zz / l});
					}
				}
			}
		}
		if (!fresh.isEmpty()) {
			it.handles.clear();
			it.handles.addAll(fresh);
		}
		if (jw.length > 0) {
			it.pos.set(jw[0][12], jw[0][13], jw[0][14]);
		}
	}

	/** 손잡이 관절과 판정 값 (0x200A50 / 0x213A38). 기본값 1 / 2 / 0.3 / 0 (0x200A50). */
	private static void handles(Item it, LevelLayout.Placement p, Tweaks tw, String node) {
		handles(it, p.object().skeleton(), p.jointWorld(), tw, node);
	}

	/** {@link #handles(Item, LevelLayout.Placement, Tweaks, String)} 를 배치 밖 GO 에 (Creator 상자: R_PERMK chest 의 handle1, 38 §21). */
	public static void handles(Item it, devple.gow.tools.anim.Skeleton sk, float[][] jw, Tweaks tw, String node) {
		var joints = sk.joints;
		for (int n = 1; n <= 8; n++) {
			String want = "handle" + n;
			for (int j = 0; j < joints.size() && j < jw.length; j++) {
				if (joints.get(j).name().equalsIgnoreCase(want)) {
					float[] m = jw[j];
					float zx = m[8], zz = m[10], l = (float) Math.hypot(zx, zz);
					if (l > 1e-4f) {
						it.handles.add(new float[]{m[12], m[13], m[14], zx / l, zz / l});
					}
				}
			}
		}
		if (tw != null && !it.handles.isEmpty()) {
			it.grabDy = f(tw, node, H_GRAB_DY, 1f) * 16f;
			it.grabR = f(tw, node, H_GRAB_R, 2f) * 16f;
			float ang = tw.f(node, "Handle Angle", Float.NaN);
			it.grabCos = Float.isNaN(ang) ? 0.3f : (float) Math.cos(Math.toRadians(ang));
			it.grabBack = f(tw, node, H_GRAB_BACK, 0f) * 16f;
		}
	}

	private static float f(Tweaks tw, String node, int hash, float def) {
		var v = tw.rawHash(node, hash);
		return v.isPresent() ? Float.intBitsToFloat(v.getAsInt()) : def;
	}

	/** 영웅 앞 점 높이·앞 (0x1C3918: 위치 + 위·17.6 + 정면·9.6). */
	public static final float GRAB_UP = 17.6f, GRAB_FWD = 9.6f;

	/**
	 * R2 로 잡힐 것 (0x1C3F18 → 0x1C3918): 손잡이가 있으면 영웅 앞 점 P 가 손잡이 J 와 |Δy| &lt; grabDy, 수평 &lt; grabR, 정면·축 &lt; −cos,
	 * (P − (J − back·축))·축 &gt; 0 — 손잡이 쪽을 보고 손잡이 앞에 있어야. 손잡이가 없으면 {@link #nearest(Vector3f, float)}.
	 * @return {아이템, 손잡이} 또는 null
	 */
	public Object[] grabbable(Vector3f feet, Vector3f fwd, float reach) {
		Object[] best = null;
		float bd = Float.MAX_VALUE;
		float px = feet.x + fwd.x * GRAB_FWD, py = feet.y + GRAB_UP, pz = feet.z + fwd.z * GRAB_FWD;
		for (Item it : items) {
			if (it.done && it.kind != Kind.PUSH || it.hidden) {
				continue;
			}
			if (it.handles.isEmpty()) {
				float d = (float) Math.hypot(it.pos.x - feet.x, it.pos.z - feet.z) - it.radius;
				if (d <= reach && Math.abs(it.pos.y - feet.y) < 64 && d < bd) {
					bd = d;
					best = new Object[]{it, null};
				}
				continue;
			}
			for (float[] h : it.handles) {
				float dx = px - h[0], dz = pz - h[2], d = (float) Math.hypot(dx, dz);
				if (Math.abs(h[1] - py) < it.grabDy && d < it.grabR && fwd.x * h[3] + fwd.z * h[4] < -it.grabCos
						&& (px - (h[0] - it.grabBack * h[3])) * h[3] + (pz - (h[2] - it.grabBack * h[4])) * h[4] > 0 && d < bd) {
					bd = d;
					best = new Object[]{it, h};
				}
			}
		}
		return best;
	}

	/**
	 * 오브젝트 애니 길이: 이름이 맞는 act (CSM TWK #fea292e6, 예 쌍발차기 문 "Default" 0.9 초), 없으면 가장 긴 act.
	 * 길이 0 인 act 만 있으면 0 (DEST03 뿔피리 — 붙자마자 완료). ANM 이 없으면 def.
	 */
	static float animLength(LevelData lv, String anm, String act, float def) {
		if (anm == null) {
			return def;
		}
		try {
			devple.gow.tools.anim.AnimationSet set = devple.gow.tools.anim.AnimationSet.load(lv.wad, anm);
			float best = -1;
			for (devple.gow.tools.anim.Animation a : set.all()) {
				if (act != null && a.name.equalsIgnoreCase(act)) {
					return a.duration;
				}
				best = Math.max(best, a.duration);
			}
			return best >= 0 ? best : def;
		} catch (RuntimeException ex) {
			return def;
		}
	}

	private static final int SHOW_R2 = 0xca686863;

	private static boolean showR2(Tweaks tw, String node) {
		return tw != null && (tw.rawHash(node, SHOW_R2).orElse(0) & 1) != 0;
	}

	/** Creator "chest" (unk5 = CSM 템플릿, unk6 = 과제 id [추정: ATHN01D Chest4 unk6 = 1, ChestMessage 가 1023 인자 1 == 1 을 기다림]). */
	public Item addChest(String owner, Vector3f pos, int csmTemplate, int taskId, Tweaks tw) {
		String node = String.format("/TweakTemplates/CSM/%03d/", csmTemplate);
		String grab = tw != null ? tw.sHash(node, CSM_HERO_ANIM, "chestGrab") : "chestGrab";
		String open = tw != null ? tw.sHash(node, CSM_HERO_ANIM2, "chestOpen") : "chestOpen";
		if (open == null || open.isEmpty() || Character.isDigit(open.charAt(0))) {
			open = "chestOpen";
		}
		String label = tw != null ? tw.s(node, "Instance Name", "chest") : "chest";
		Item it = new Item(Kind.CHEST, owner, pos, csmTemplate, taskId, grab, open, label, 20);
		// 상자 템플릿 (CSM 000 "chest open" 처럼 비트 키가 없으면) = 누르고 있기 [추정: 다른 상자 템플릿 002–065 는 모두 1]
		it.hold = tw == null || (tw.rawHash(node, CSM_HOLD_FLAG).orElse(1) & 1) != 0;
		it.showPrompt = showR2(tw, node);
		if (tw != null) {
			it.chestType = (short) tw.rawHash(node, CHEST_TYPE).orElse(0); // s16 (상위 2바이트 = 덤프 잡음, 41 §1.1 과 같은 형)
			for (int i = 0; i < CHEST_ORBS.length; i++) {
				it.chestOrbs[i] = tw.rawHash(node, CHEST_ORBS[i]).orElse(-1);
			}
			it.chestCycle = f(tw, node, CHEST_CYCLE, 0f);
		}
		it.chestShown = it.chestType;
		return add(it);
	}

	/** p 에서 수평 반경(오브젝트 반지름 + reach) 안, 높이 차 64 이내의 가장 가까운 것 (끝난 CSM/상자 제외). */
	public Item nearest(Vector3f p, float reach) {
		Item best = null;
		float bd = Float.MAX_VALUE;
		for (Item it : items) {
			if (it.done && it.kind != Kind.PUSH || it.hidden) {
				continue;
			}
			float d = (float) Math.hypot(it.pos.x - p.x, it.pos.z - p.z) - it.radius;
			if (d <= reach && Math.abs(it.pos.y - p.y) < 64 && d < bd) {
				bd = d;
				best = it;
			}
		}
		return best;
	}

	public Item byOwner(String owner) {
		for (Item it : items) {
			if (it.owner.equalsIgnoreCase(owner)) {
				return it;
			}
		}
		return null;
	}
}
