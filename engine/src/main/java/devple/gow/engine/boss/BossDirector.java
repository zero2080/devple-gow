package devple.gow.engine.boss;

import devple.gow.tools.content.Content;
import devple.gow.engine.combat.EnemyFighter;
import devple.gow.engine.input.Input;
import devple.gow.engine.script.ScriptActor;
import devple.gow.tools.wad.WadFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * 레벨 → 보스전 등록표. GameScene 은 레벨 진입 시 {@link #create} 로 물어보고, null 이면 일반 레벨.
 * <pre>
 *  ATHN01E  히드라 (bha, AI1 250)                     HydraFight
 *  ATHN02D  메두사 여왕 (me3, 062)                    MedusaQueenFight
 *  ATHN08A  아레스 1단계 (bar, 600)                   AresFight(1)
 *  ATHN08B  분신 크레토스 / 가족 (evi 666–669, xmc 660)    EvilKratosFight (가족 보호·포옹, 분신 공격 휘말림)
 *  ATHN08C  아레스 3단계 (ba2, 601)                   AresFight(3)
 *  PAND03G  판도라 수호자 BMI (bmi, 200)               BmiFight (전용 클래스 Think/TakeDamage, 올라타기·통나무)
 * </pre>
 */
public final class BossDirector {

	private BossDirector() {
	}

	public static boolean hostsBoss(String level) {
		return switch (level.toUpperCase(Locale.ROOT)) {
			case "ATHN01E", "ATHN02D", "ATHN08A", "ATHN08B", "ATHN08C", "PAND03G" -> true;
			default -> false;
		};
	}

	/** 보스전 (없으면 null). 로드 실패도 null (경고 출력) — 일반 레벨로 계속. */
	public static BossFight create(String level, Path rom, WadFile permk) {
		String lv = level.toUpperCase(Locale.ROOT);
		if (!hostsBoss(lv) || !Files.exists(Content.file(rom, lv + ".WAD"))) {
			return null;
		}
		try {
			return switch (lv) {
				case "ATHN01E" -> new HydraFight(rom, permk);
				case "ATHN02D" -> new MedusaQueenFight(rom, permk);
				case "ATHN08A" -> new AresFight(lv, rom, permk, 1);
				case "ATHN08C" -> new AresFight(lv, rom, permk, 3);
				case "ATHN08B" -> new EvilKratosFight(rom, permk);
				case "PAND03G" -> new BmiFight(rom, permk);
				default -> null;
			};
		} catch (IOException | RuntimeException ex) {
			System.out.println("보스 리소스 로드 실패 " + lv + ": " + ex);
			return null;
		}
	}

	/** 레벨 WAD 안의 적 리소스만 구성 (전용 연출 없음, 일반 AI 로 싸움). */
	static final class ResourceOnlyFight extends BossFight {

		ResourceOnlyFight(String level, Path rom, WadFile permk, int[][] templateSet, String[] skeletons) throws IOException {
			super(level);
			var assets = new devple.gow.engine.asset.WadAssets(Content.file(rom, level + ".WAD"));
			for (int i = 0; i < templateSet.length; i++) {
				try {
					kinds.put(templateSet[i][0], BossKinds.load(assets, permk, List.of(), skeletons[i], templateSet[i][0], templateSet[i][1]));
				} catch (RuntimeException ex) {
					System.out.println("보스 리소스 " + level + " " + templateSet[i][0] + " 실패: " + ex.getMessage());
				}
			}
			bossTemplates.add(templateSet[0][0]);
		}

		@Override
		protected void bind(EnemyFighter f, ScriptActor a) {
			f.brain().engage();
		}

		@Override
		protected void tick(Input in, float dt) {
			if (boss.hp <= 0) {
				markDefeated();
			} else if (boss.combat().active()) {
				setPhase("전투");
			}
		}
	}
}
