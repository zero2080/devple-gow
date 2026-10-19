package devple.gow.engine.script;

import java.util.Deque;

/**
 * ESC VM 이 엔진에 요청하는 기능. scope: 0 Entity, 1 Internal(엔진 전역 = GameMgr 메시지), 2 GlobalData, 3 LevelData.
 * 엔진 함수의 인자 수는 바이트코드에 없으므로 호스트가 함수별로 스택에서 직접 꺼낸다 (원작도 네이티브 함수가 스스로 pop).
 */
public interface EscHost {

	/** 반환형: 0 float, 1 int, 2 bool, 3 string. 반환값은 VM 이 스택에 push 한다. */
	Object call(EscContext ctx, int scope, int func, int returnType, Deque<Object> stack);

	Object getVar(EscContext ctx, int scope, int id, int type);

	void setVar(EscContext ctx, int scope, int id, int type, Object value);
}
