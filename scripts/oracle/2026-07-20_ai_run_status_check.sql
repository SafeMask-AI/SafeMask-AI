-- ============================================================================
-- AiRunStatus enum 확장에 따른 체크 제약조건 갱신 (2026-07-20, feat/30)
--
-- 배경:
--   MASK_AI_RUN.STATUS 컬럼의 체크 제약조건이 예전 enum 값 목록으로 만들어져 있어,
--   나중에 추가된 CANCELLED 값으로의 UPDATE가 ORA-02290으로 거부됩니다.
--   (ddl-auto=validate/update는 기존 체크 제약을 스스로 갱신하지 못함)
--   이 때문에 사용자가 응답 생성을 정지해도 서버의 취소 상태 전이가 실패하고,
--   답변 생성 스레드까지 함께 오류로 종료되는 문제가 있었습니다.
--
-- 실행 방법:
--   애플리케이션을 내린 상태에서 서비스 계정(TEST01)으로 이 스크립트 전체를 실행하세요.
--   상태 값이 또 늘어나면 아래 v_statuses 목록에 추가한 뒤 재실행하면 됩니다. (재실행 안전)
--
-- 참고:
--   - 12.2 미만 Oracle에는 search_condition_vc 컬럼이 없어 LONG 타입인
--     search_condition을 PL/SQL 변수로 받아 판별합니다.
--   - 테이블명 변경(#1, MASK_ 접두사) 전/후 어느 스키마에서든 동작하도록
--     옛 이름과 새 이름을 모두 대상으로 합니다.
-- ============================================================================

SET SERVEROUTPUT ON

DECLARE
    -- AiRunStatus enum과 반드시 일치해야 하는 허용 값 목록
    v_statuses VARCHAR2(500) :=
        '''PENDING'',''PREVIEW'',''APPROVED'',''REJECTED'',''CALLING'',' ||
        '''COMPLETED'',''CANCELLED'',''FAILED''';
    v_cond     VARCHAR2(32767);
BEGIN
    -- 실제 존재하는 대상 테이블만 순회 (테이블명 변경 전/후 모두 커버)
    FOR t IN (
        SELECT table_name FROM user_tables
        WHERE table_name IN ('AI_RUN', 'MASK_AI_RUN')
    ) LOOP
        -- STATUS 컬럼에 걸린 체크 제약조건 중 IN-목록 형태만 골라 제거
        -- (NOT NULL 체크는 조건문에 괄호가 없어 걸러짐)
        FOR c IN (
            SELECT uc.constraint_name, uc.search_condition
            FROM user_constraints uc
            JOIN user_cons_columns ucc
                ON uc.constraint_name = ucc.constraint_name
            WHERE uc.constraint_type = 'C'
                AND uc.table_name = t.table_name
                AND ucc.column_name = 'STATUS'
        ) LOOP
            v_cond := c.search_condition;  -- LONG → VARCHAR2 (PL/SQL 대입으로만 가능)
            IF UPPER(v_cond) LIKE '%IN%(%' THEN
                EXECUTE IMMEDIATE 'ALTER TABLE ' || t.table_name ||
                    ' DROP CONSTRAINT ' || c.constraint_name;
                DBMS_OUTPUT.PUT_LINE(t.table_name || '.' || c.constraint_name || ' 제거');
            END IF;
        END LOOP;

        -- 확장된 값 목록으로 다시 생성
        EXECUTE IMMEDIATE 'ALTER TABLE ' || t.table_name ||
            ' ADD CONSTRAINT CK_' || t.table_name || '_STATUS' ||
            ' CHECK (STATUS IN (' || v_statuses || '))';
        DBMS_OUTPUT.PUT_LINE('CK_' || t.table_name || '_STATUS 재생성 완료');
    END LOOP;
END;
/

COMMIT;
