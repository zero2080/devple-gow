package devple.gow.engine.game;

/**
 * 업그레이드 대상. key = FLP_HUDK 변수 접두사 (UpgradeTable 의 키), maxLevel = 0 기반 최대 레벨.
 * 마법 아이템 ID 는 11 문서 §2 (3..6 = 마법 4종).
 */
public enum Weapon {
	BLADES("Blades", 4, 0),
	POSEIDON("Lightning", 2, 3),
	MEDUSA("Medusa", 2, 4),
	ZEUS("Thunderbolt", 2, 5),
	HADES("RaiseDead", 2, 6),
	ARTEMIS("BerserkBlade", 2, 0);

	public final String key;
	public final int maxLevel;
	/** 마법이면 아이템 ID, 아니면 0. */
	public final int itemId;

	Weapon(String key, int maxLevel, int itemId) {
		this.key = key;
		this.maxLevel = maxLevel;
		this.itemId = itemId;
	}

	public static Weapon ofItem(int id) {
		for (Weapon w : values()) {
			if (w.itemId == id) {
				return w;
			}
		}
		return null;
	}
}
