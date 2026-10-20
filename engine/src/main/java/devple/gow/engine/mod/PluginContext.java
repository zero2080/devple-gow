package devple.gow.engine.mod;

import devple.gow.engine.combat.EnemyFighter;
import devple.gow.tools.content.Content;

import java.util.Properties;
import java.util.function.Function;

/** 플러그인에 주는 자리 (49 문서 §13). */
public final class PluginContext {

	PluginContext() {
	}

	/** 콘텐츠 계층 (설정·모드 파일 조회). */
	public Content content() {
		return Content.get();
	}

	/** 계층마다 덮어 합친 .properties ({@link Content#properties}). */
	public Properties properties(String rel) {
		return Content.properties(rel);
	}

	/**
	 * 적 제어 교체. key = AI1 번호 ("50" / "050"), 템플릿 Instance Name, 적 WAD 이름 ("r_min" / "min"), 또는 "*" (모두) —
	 * 대소문자 무시, 같은 적에 여럿이면 {@link devple.gow.engine.combat.AiOverrides#keys} 순서로 구체적인 열쇠가 이긴다.
	 * factory 는 적 1체 생성마다 불린다 (null 을 돌려주면 원래 AI).
	 */
	public void replaceEnemy(String key, Function<EnemyFighter, EnemyController> factory) {
		Plugins.register(key, factory);
	}
}
