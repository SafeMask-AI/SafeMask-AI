-- ============================================================================
-- MaskingType enum 확장에 따른 체크 제약조건 갱신 (2026-07-06, feat/2)
--
-- 배경:
--   MASKING_RULE.TYPE / MASKING_ENTITY.TYPE 컬럼의 체크 제약조건이
--   예전 enum 값 목록(NAME~CUSTOM 10종)으로 만들어져 있어,
--   새로 추가된 PASSPORT / DRIVER_LICENSE / VEHICLE_NUMBER 값의 INSERT가
--   ORA-02290으로 거부됩니다. (ddl-auto=validate라 앱이 스스로 못 고침)
--
-- 실행 방법:
--   애플리케이션을 내린 상태에서 서비스 계정(TEST01)으로 이 스크립트 전체를 실행하세요.
--   새 유형이 또 늘어나면 아래 v_types 목록에 추가한 뒤 재실행하면 됩니다. (재실행 안전)
--
-- 참고:
--   - 12.2 미만 Oracle에는 search_condition_vc 컬럼이 없어 LONG 타입인
--     search_condition을 PL/SQL 변수로 받아 판별합니다.
--   - 테이블명 변경(#1, MASK_ 접두사) 전/후 어느 스키마에서든 동작하도록
--     옛 이름과 새 이름을 모두 대상으로 합니다.
-- ============================================================================

SET SERVEROUTPUT ON

DECLARE
    -- MaskingType enum과 반드시 일치해야 하는 허용 값 목록
    v_types VARCHAR2(500) :=
        '''NAME'',''PHONE'',''EMAIL'',''RRN'',''CARD_NUMBER'',''ACCOUNT_NUMBER'',''ADDRESS'',' ||
        '''EMPLOYEE_NO'',''IP'',''PASSPORT'',''DRIVER_LICENSE'',''VEHICLE_NUMBER'',''CUSTOM''';
    v_cond  VARCHAR2(32767);
BEGIN
    -- 실제 존재하는 대상 테이블만 순회 (테이블명 변경 전/후 모두 커버)
    FOR t IN (
        SELECT table_name FROM user_tables
        WHERE table_name IN ('MASKING_RULE', 'MASKING_ENTITY',
                             'MASK_MASKING_RULE', 'MASK_MASKING_ENTITY')
    ) LOOP
        -- TYPE 컬럼에 걸린 체크 제약조건 중 IN-목록 형태만 골라 제거
        -- (NOT NULL 체크는 조건문에 괄호가 없어 걸러짐)
        FOR c IN (
            SELECT uc.constraint_name, uc.search_condition
            FROM user_constraints uc
            JOIN user_cons_columns ucc
                ON uc.constraint_name = ucc.constraint_name
            WHERE uc.constraint_type = 'C'
                AND uc.table_name = t.table_name
                AND ucc.column_name = 'TYPE'
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
            ' ADD CONSTRAINT CK_' || t.table_name || '_TYPE' ||
            ' CHECK (TYPE IN (' || v_types || '))';
        DBMS_OUTPUT.PUT_LINE('CK_' || t.table_name || '_TYPE 재생성 완료');
    END LOOP;
END;
/

COMMIT;
