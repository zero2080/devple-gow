package devple.gow.engine.game;

import java.util.EnumMap;
import java.util.Map;

/**
 * 플레이어 영속 상태 + 성장 규칙. 원작 PlayerProgress(0x304440, 0x90바이트)에 대응 (11 문서 §4, §8.2).
 * HUD 는 이 객체의 뷰로만 동작한다.
 */
public final class PlayerState {

	private final GameRules rules;
	public int costume;
	public float hp;
	public float mp;
	/**
	 * 마법 재생 속도, MP/초 (원작 PlayerProgress +0x64 = 0x3044A4). 스크립트 Internal 0x12 가 쓴다 (GameMgr_OnMessage 0x1B0B34,
	 * 표 0x2B5E20[0x12]) — ATHN02D 미노타우로스 방: RewardPlayer 10, 문이 열린 뒤 TurnFaceOffFinal 0 (ATHN05B 0). 영웅 갱신 0x1D6F20 이
	 * 게임 중 매 프레임 MP += 값 × 1/60 × 시간 배율 (0..최대 자르기). {@link #regenMagic}
	 */
	public float mpRegen;
	/**
	 * HUD 미터 표시 요청 횟수 (원작 Progress +0x1C0 = 1: 오브 흡수 0x1D77B0 (Orb_Update 0x226710), AddItem 0x1D8CF0,
	 * 업그레이드 메뉴 닫힘 0x1D7700). 값이 그대로여도 (가득 찬 HP 에 녹색 오브) 미터가 뜬다. 저장하지 않는다.
	 */
	public int meterPokes;
	public float rage;
	public int redOrbs;
	public int hpLevel;
	public int mpLevel;
	public int gorgonEyes;
	public int phoenixFeathers;
	public int deaths;
	public int itemFlags;
	/** 첫 안내 비트 (PlayerProgress +0x50) 와 안내 1 / 0x13 누적 (+0x54 / +0x55) — {@link #firstTimeHint}. 세이브 대상, 체크포인트 재시작에도 유지. */
	public int hintMask, hintCount1, hintCount19;
	public Weapon selectedMagic;
	/** 아르테미스의 검 장착 중 (원작 player.flags&amp;0x20). BERSERKENTER/EXIT 의 SCR_Enable/DisableBerserkBlade 이벤트로 바뀐다. */
	public boolean artemisEquipped;
	public final Map<Weapon, Integer> level = new EnumMap<>(Weapon.class);
	public final Map<Weapon, Integer> invested = new EnumMap<>(Weapon.class);

	public PlayerState(GameRules rules) {
		this.rules = rules;
		for (Weapon w : Weapon.values()) {
			level.put(w, 0);
			invested.put(w, 0);
		}
		hp = maxHp();
	}

	public float maxHp() {
		return rules.maxHp(hpLevel);
	}

	/**
	 * FirstTimeHint 0x1DBEB0 [확실]: 플레이 2 초 전 (progress +0x4C &lt; 2.0) 이거나 비트 (+0x50, 1 &lt;&lt; id) 가 이미 서 있으면 거짓. 안내 1 은 3 번째
	 * (+0x54), 0x13 은 8 번째 (+0x55) 부터. 그 뒤 비트를 세우고 참 — 호출자가 메시지 500 + id 가 있으면 띄운다 (0x1B9528 줄 수 &gt; 0 → 0x21D980).
	 * 부르는 곳: 기술 SCRIPT 이벤트 SCR_FirstTimeMsg (0x19CD20, idx = id — R_SKB 방패 막기 SKBBLOCK 19 → 519, 영웅 MAIMEWGRAB 1 → 501,
	 * MAIMIN01 7 → 507, BOLTPULLOUT 9 → 509 …), 코드 (18 오브 0x1DBE98, 21 고르곤에 메두사 시선 0x1964A4, 10 · 12 · 2 · 6 · 17 · 0 · 3 · 16).
	 */
	public boolean firstTimeHint(int id, float playTime) {
		if (playTime < 2f || id < 0 || id >= 32 || (hintMask & (1 << id)) != 0) {
			return false;
		}
		if (id == 1 && ++hintCount1 < 3) {
			return false;
		}
		if (id == 0x13 && ++hintCount19 < 8) {
			return false;
		}
		hintMask |= 1 << id;
		return true;
	}

	public float maxMp() {
		return rules.maxMp(mpLevel);
	}

	public float meterHoldTime() {
		return rules.meterHoldTime();
	}

	/**
	 * 영웅 갱신의 마법 재생 (원작 sub_001D6CF0 0x1D6F00–0x1D6F88) [확실: 코드]: 게임 상태 0xB (플레이 중) 이고 0x29DB58 · 영웅 +0x10 이 0 일 때
	 * d = 0x3044A4 × (1/60) × (vt+0xC8 × 0.25 + 0.75) × +0x16C [시간 배율 추정 1], d ≠ 0 이면 MP = min(max(MP + d, 0), 최대 MP).
	 */
	public void regenMagic(float dt) {
		if (mpRegen != 0) {
			mp = Math.min(Math.max(mp + mpRegen * dt, 0), maxMp());
		}
	}

	public void addRedOrbs(int n) {
		redOrbs = Math.min(redOrbs + n, GameRules.MAX_RED_ORBS);
		// SetRedOrbs 0x1DBE10: 붉은 오브 + 블레이드에 넣은 오브 ≥ 1500 이고 무기·마법 레벨 6 개가 모두 0 이면 (안내 18 비트가 없을 때 SND_LEVELUPCARD) 안내 18
		if (onHint != null && redOrbs + invested.get(Weapon.BLADES) >= 1500 && level.values().stream().allMatch(l -> l == 0)
				&& (hintMask & (1 << 18)) == 0) {
			onHint.accept(18);
		}
	}

	/** 코드가 부르는 첫 안내 (게임 씬이 소리·메시지를 낸다). 저장하지 않는다. */
	public transient java.util.function.IntConsumer onHint;

	/** 오브 획득. type 0 = 체력(녹색), 1 = 마법(청색), 2 = 경험(붉은색). points = 템플릿의 Points. */
	public void pickupOrb(int type, float points) {
		meterPokes++;
		float v = points * rules.orbMult(costume, type);
		switch (type) {
			case 0 -> hp = Math.min(hp + v, maxHp());
			case 1 -> mp = Math.max(0, Math.min(mp + v, maxMp()));
			default -> addRedOrbs((int) v);
		}
	}

	/**
	 * 업그레이드 메뉴에서 오브 투자 (원작: 프레임당 1개, 버튼 유지 시 10개). 남은 오브는 다음 레벨로 이월.
	 *
	 * @return 레벨이 올랐으면 true (해당 무기/마법 WAD 교체 필요)
	 */
	public boolean invest(Weapon w, int amount) {
		if (!owned(w) || level.get(w) >= w.maxLevel) {
			return false;
		}
		int k = Math.min(amount, redOrbs);
		redOrbs -= k;
		invested.merge(w, k, Integer::sum);
		boolean up = false;
		while (level.get(w) < w.maxLevel && invested.get(w) >= rules.upgradeCost(w, level.get(w))) {
			invested.merge(w, -rules.upgradeCost(w, level.get(w)), Integer::sum);
			level.merge(w, 1, Integer::sum);
			up = true;
		}
		return up;
	}

	/** 다음 레벨까지 필요한 오브 (HUD NeededOrbs). */
	public int neededOrbs(Weapon w) {
		int lv = level.get(w);
		return lv >= w.maxLevel ? 0 : rules.upgradeCost(w, lv) - invested.get(w);
	}

	public boolean owned(Weapon w) {
		return w == Weapon.BLADES || (itemFlags & (1 << (itemId(w) - 1))) != 0;
	}

	private static int itemId(Weapon w) {
		// 아르테미스의 검의 아이템 ID 는 미확정 (11 문서 §9) → 마법 4종 다음 번호로 가정
		return w.itemId != 0 ? w.itemId : 7;
	}

	/** 아이템 획득 (11 문서 §8.2 addItem). */
	public void addItem(int id, int n) {
		meterPokes++;
		boolean isNew = (itemFlags & (1 << (id - 1))) == 0;
		itemFlags |= 1 << (id - 1);
		if (id >= 3 && id <= 6 && isNew) {
			selectedMagic = Weapon.ofItem(id);
			if (mpLevel == 0) {
				mpLevel = 1;
				mp = maxMp();
			}
		} else if (id == 10) {
			int before = gorgonEyes / rules.shardsPerLevel();
			gorgonEyes = Math.min(gorgonEyes + n, GameRules.MAX_SHARDS);
			hpLevel = Math.min(GameRules.HP_LEVELS - 1, hpLevel + gorgonEyes / rules.shardsPerLevel() - before);
			hp = maxHp();
		} else if (id == 11) {
			int before = phoenixFeathers / rules.shardsPerLevel();
			phoenixFeathers = Math.min(phoenixFeathers + n, GameRules.MAX_SHARDS);
			mpLevel = Math.min(GameRules.MP_LEVELS - 1, mpLevel + phoenixFeathers / rules.shardsPerLevel() - before);
			mp = maxMp();
		}
	}

	/** 제우스의 선물 아이템 카드 (메시지 31): kind 1 = HP 레벨 +1, 2 = MP 레벨 +1. */
	public void itemCard(int kind) {
		meterPokes++;
		if (kind == 1) {
			hpLevel = Math.min(GameRules.HP_LEVELS - 1, hpLevel + 1);
			hp = maxHp();
		} else if (kind == 2) {
			mpLevel = Math.min(GameRules.MP_LEVELS - 1, mpLevel + 1);
			mp = maxMp();
		}
	}

	/** 체크포인트 재시작 시 HP 보정 (11 문서 §8.2 restartCheckpoint). */
	public void onCheckpointRestart(float savedHp) {
		hp = Math.max(savedHp, 25);
		if (deaths >= 4) {
			hp = Math.min(hp + (deaths - 3) * 10, maxHp());
		}
		if (savedHp < 75) {
			hp = Math.min(hp, 75);
		}
	}
}
