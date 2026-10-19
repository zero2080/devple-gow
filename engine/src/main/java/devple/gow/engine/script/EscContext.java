package devple.gow.engine.script;

import devple.gow.tools.script.EscScript;

/** 실행 중인 엔티티 정보 (스크립트 이름, 엔티티). */
public record EscContext(String script, EscScript.Entity entity) {
}
