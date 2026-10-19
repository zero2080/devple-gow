package devple.gow.tools.progress;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/**
 * 플레이어 영속 상태 (EE 0x304440, 0x90 바이트). 세이브 스트림의 첫 블록으로 그대로 기록된다
 * (Save_PlayerBlock 0x1DBFD8 → 0x306828 사본, Load_PlayerBlock 0x1DC130 이 되돌림).
 * 새 게임 초기화는 Progress_Reset 0x1D6710 / 0x1D6620.
 */
public record PlayerProgress(
		float[] matrix, int playerFlags, int unk44, int deaths, float playTime, int hintMask, byte[] hintCounters,
		float health, float magic, float rage, float godModeTimer, int redOrbs, int[] investedOrbs,
		int healthLevel, int magicLevel, int[] weaponLevels, int selectedMagic,
		int gorgonEyes, int phoenixFeathers, int sirenPieces, int itemFlags, byte[] unk84) {

	public static final int SIZE = 0x90;

	/** 업그레이드 대상 순서 (0x6A.. u16, 0x78.. u8 공통). */
	public static final List<String> WEAPONS = List.of("Lightning", "Medusa", "Thunderbolt", "RaiseDead", "BerserkBlade", "Blades");

	/** 필드 명세: 오프셋, 타입, 이름, 설명, 신뢰도. */
	public record Field(int offset, String type, String name, String desc, boolean certain) {
	}

	public static final List<Field> LAYOUT = List.of(
			new Field(0x00, "f32[16]", "matrix", "플레이어 월드 행렬 (Player+0x10..0x4F 복사)", true),
			new Field(0x40, "u32", "playerFlags", "Player+0x174 & 0xFFFF3BFF (상태 비트, 의미 미상)", false),
			new Field(0x44, "u32", "unk44", "리셋 시 0, 다른 참조 미발견", false),
			new Field(0x48, "u32", "deaths", "사망 횟수. deaths % 'Deaths Before Easy Offer'(5)==0 이면 쉬움 제안, 재시작 HP 보정", true),
			new Field(0x4C, "f32", "playTime", "누적 플레이 시간(초), 매 프레임 +dt. 세이브 슬롯 \"%d:%02d:%02d\"", true),
			new Field(0x50, "u32", "hintMask", "첫 안내 메시지 표시 비트 (bit n → 메시지 500+n)", true),
			new Field(0x54, "u8[4]", "hintCounters", "[0] 안내1 반복 횟수(3회째 표시) [1] 안내 0x13 횟수(8회째) [2,3] 미사용?", true),
			new Field(0x58, "f32", "health", "현재 HP (저장 시 날아오는 녹색 오브 포함, 최대 HP 로 클램프)", true),
			new Field(0x5C, "f32", "magic", "현재 MP (Max Magic Level[magicLevel] 로 클램프)", true),
			new Field(0x60, "f32", "rage", "신들의 분노 게이지 (리셋 100) → HUD CoolMeter_Value", true),
			new Field(0x64, "f32", "godModeTimer", "≠0 이면 신들의 분노 발동 중 (GameMgr 메시지가 설정)", false),
			new Field(0x68, "u16", "redOrbs", "보유 붉은 오브 (최대 60000)", true),
			new Field(0x6A, "u16[6]", "investedOrbs", "무기별 투자 오브: Lightning, Medusa, Thunderbolt, RaiseDead, BerserkBlade, Blades", true),
			new Field(0x76, "u8", "healthLevel", "최대 HP 레벨 0..4 (100/125/150/175/200)", true),
			new Field(0x77, "u8", "magicLevel", "최대 MP 레벨 0..5 (0/100/125/150/175/200)", true),
			new Field(0x78, "u8[6]", "weaponLevels", "Lightning, Medusa, Thunderbolt, RaiseDead, BerserkBlade (0..2), Blades (0..4)", true),
			new Field(0x7E, "u8", "selectedMagic", "선택된 마법 아이템 ID (3..6, 0=없음)", true),
			new Field(0x7F, "u8", "gorgonEyes", "고르곤의 눈 (아이템 10, 최대 3×6=18)", true),
			new Field(0x80, "u8", "phoenixFeathers", "불사조 깃털 (아이템 11, 최대 18)", true),
			new Field(0x81, "u8", "sirenPieces", "아이템 14 (HUD SirenPiece_Count, 최대 2)", false),
			new Field(0x82, "u16", "itemFlags", "보유 아이템 비트 (bit = 아이템ID-1). 0x4 Lightning … 0x40 BerserkBlade, 0x100 Trident", true),
			new Field(0x84, "u8[12]", "unk84", "참조 미발견 (패딩 추정)", false));

	public static PlayerProgress parse(ByteBuffer src) {
		ByteBuffer b = src.duplicate().order(ByteOrder.LITTLE_ENDIAN);
		float[] m = new float[16];
		for (int i = 0; i < 16; i++) {
			m[i] = b.getFloat(i * 4);
		}
		byte[] hc = new byte[4];
		b.get(0x54, hc);
		int[] inv = new int[6];
		int[] lv = new int[6];
		for (int i = 0; i < 6; i++) {
			inv[i] = Short.toUnsignedInt(b.getShort(0x6A + i * 2));
			lv[i] = b.get(0x78 + i) & 0xFF;
		}
		byte[] tail = new byte[12];
		b.get(0x84, tail);
		return new PlayerProgress(m, b.getInt(0x40), b.getInt(0x44), b.getInt(0x48), b.getFloat(0x4C), b.getInt(0x50), hc,
				b.getFloat(0x58), b.getFloat(0x5C), b.getFloat(0x60), b.getFloat(0x64), Short.toUnsignedInt(b.getShort(0x68)), inv,
				b.get(0x76) & 0xFF, b.get(0x77) & 0xFF, lv, b.get(0x7E) & 0xFF,
				b.get(0x7F) & 0xFF, b.get(0x80) & 0xFF, b.get(0x81) & 0xFF, Short.toUnsignedInt(b.getShort(0x82)), tail);
	}

	/** 새 게임 상태 (Progress_Reset 0x1D6710): 전부 0, rage=100, HP=플레이어 최대 HP. */
	public static PlayerProgress newGame(float maxHealth) {
		float[] id = new float[16];
		id[0] = id[5] = id[10] = id[15] = 1;
		return new PlayerProgress(id, 0, 0, 0, 0, 0, new byte[4], maxHealth, 0, 100, 0, 0, new int[6], 0, 0, new int[6], 0,
				0, 0, 0, 0, new byte[12]);
	}
}
