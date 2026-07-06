-- ============================================================================
-- FileAssetStatus enum에 GENERATED 추가에 따른 체크 제약조건 갱신 (2026-07-06, feat/2)
--
-- 배경:
--   AI 생성 파일 다운로드 기능이 추가되면서 FILE_ASSET.STATUS에
--   GENERATED 값이 새로 저장됩니다. 테이블 생성 시점의 체크 제약조건에는
--   이 값이 없어 INSERT가 ORA-02290으로 거부될 수 있습니다.
--
-- 실행 방법:
--   서비스 계정으로 전체 실행. (재실행 안전, 테이블명 변경 전/후 모두 대응)
-- ============================================================================

SET SERVEROUTPUT ON

DECLARE
    -- FileAssetStatus enum과 반드시 일치해야 하는 허용 값 목록
    v_statuses VARCHAR2(300) :=
        '''UPLOADED'',''EXTRACTED'',''MASKED'',''GENERATED'',''FAILED'',''DELETED''';
    v_cond     VARCHAR2(32767);
BEGIN
    FOR t IN (
        SELECT table_name FROM user_tables
        WHERE table_name IN ('FILE_ASSET', 'MASK_FILE_ASSET')
    ) LOOP
        -- STATUS 컬럼의 기존 IN-목록 체크 제약조건 제거 (NOT NULL 체크는 유지)
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

        EXECUTE IMMEDIATE 'ALTER TABLE ' || t.table_name ||
            ' ADD CONSTRAINT CK_' || t.table_name || '_STATUS' ||
            ' CHECK (STATUS IN (' || v_statuses || '))';
        DBMS_OUTPUT.PUT_LINE('CK_' || t.table_name || '_STATUS 재생성 완료');
    END LOOP;
END;
/

COMMIT;
