-- =============================================================================
-- 관리자 사용자 정의 마스킹 규칙 및 감사 이력 (2026-07-22, feat/38)
--
-- 배포 순서:
--   애플리케이션을 내린 뒤 서비스 계정으로 이 스크립트를 실행합니다.
--   기존 규칙은 모두 코드가 소유하는 SYSTEM/REGEX 규칙으로 이관합니다.
--   재실행해도 이미 존재하는 컬럼·테이블·시퀀스는 건너뜁니다.
-- =============================================================================

SET SERVEROUTPUT ON

DECLARE
    v_count NUMBER;

    PROCEDURE add_column_if_missing(p_column VARCHAR2, p_definition VARCHAR2) IS
    BEGIN
        SELECT COUNT(*) INTO v_count
        FROM all_tab_columns
        WHERE owner = SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA')
            AND table_name = 'MASK_MASKING_RULE' AND column_name = p_column;
        IF v_count = 0 THEN
			BEGIN
				EXECUTE IMMEDIATE 'ALTER TABLE MASK_MASKING_RULE ADD (' || p_definition || ')';
				DBMS_OUTPUT.PUT_LINE(p_column || ' 컬럼 추가 완료');
			EXCEPTION
				-- 직전 실행이나 다른 세션이 먼저 추가한 경우 재실행을 계속합니다.
				WHEN OTHERS THEN
					IF SQLCODE != -1430 THEN
						RAISE;
					END IF;
			END;
        END IF;
    END;

	PROCEDURE ensure_not_null(p_column VARCHAR2, p_definition VARCHAR2) IS
		v_nullable VARCHAR2(1);
	BEGIN
		SELECT nullable INTO v_nullable
		FROM all_tab_columns
		WHERE owner = SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA')
			AND table_name = 'MASK_MASKING_RULE' AND column_name = p_column;
		IF v_nullable = 'Y' THEN
			EXECUTE IMMEDIATE 'ALTER TABLE MASK_MASKING_RULE MODIFY (' || p_definition || ' NOT NULL)';
		END IF;
	END;
BEGIN
    add_column_if_missing('RULE_KIND', 'RULE_KIND VARCHAR2(20 CHAR)');
    add_column_if_missing('ORIGIN', 'ORIGIN VARCHAR2(20 CHAR)');
    add_column_if_missing('CREATED_BY_MEMBER_ID', 'CREATED_BY_MEMBER_ID NUMBER(19)');
    add_column_if_missing('CREATED_BY_NAME', 'CREATED_BY_NAME VARCHAR2(50 CHAR)');
    add_column_if_missing('UPDATED_BY_MEMBER_ID', 'UPDATED_BY_MEMBER_ID NUMBER(19)');
    add_column_if_missing('UPDATED_BY_NAME', 'UPDATED_BY_NAME VARCHAR2(50 CHAR)');
	add_column_if_missing('LAST_TESTED_AT', 'LAST_TESTED_AT TIMESTAMP');
	add_column_if_missing('LAST_TESTED_FINGERPRINT', 'LAST_TESTED_FINGERPRINT VARCHAR2(64 CHAR)');
	add_column_if_missing('VERSION', 'VERSION NUMBER(19)');

	-- 위 컬럼들은 블록 실행 중 동적으로 생성될 수 있습니다. Oracle은 PL/SQL 블록을
	-- 실행 전에 컴파일하므로 새 컬럼을 참조하는 이관 SQL도 동적으로 실행해야 합니다.
	EXECUTE IMMEDIATE 'UPDATE MASK_MASKING_RULE SET RULE_KIND = ''REGEX'' WHERE RULE_KIND IS NULL';
	EXECUTE IMMEDIATE 'UPDATE MASK_MASKING_RULE SET ORIGIN = ''SYSTEM'' WHERE ORIGIN IS NULL';
	EXECUTE IMMEDIATE 'UPDATE MASK_MASKING_RULE SET VERSION = 0 WHERE VERSION IS NULL';

	ensure_not_null('RULE_KIND', 'RULE_KIND VARCHAR2(20 CHAR)');
	ensure_not_null('ORIGIN', 'ORIGIN VARCHAR2(20 CHAR)');
	ensure_not_null('VERSION', 'VERSION NUMBER(19)');

    SELECT COUNT(*) INTO v_count FROM all_constraints
    WHERE owner = SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA')
		AND constraint_name = 'CK_MASK_RULE_KIND';
    IF v_count = 0 THEN
        EXECUTE IMMEDIATE 'ALTER TABLE MASK_MASKING_RULE ADD CONSTRAINT CK_MASK_RULE_KIND '
            || 'CHECK (RULE_KIND IN (''KEYWORD'', ''REGEX''))';
    END IF;

    SELECT COUNT(*) INTO v_count FROM all_constraints
    WHERE owner = SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA')
		AND constraint_name = 'CK_MASK_RULE_ORIGIN';
    IF v_count = 0 THEN
        EXECUTE IMMEDIATE 'ALTER TABLE MASK_MASKING_RULE ADD CONSTRAINT CK_MASK_RULE_ORIGIN '
            || 'CHECK (ORIGIN IN (''SYSTEM'', ''CUSTOM''))';
    END IF;

    SELECT COUNT(*) INTO v_count FROM all_sequences
    WHERE sequence_owner = SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA')
		AND sequence_name = 'SAFEMASK_MASK_RULE_AUDIT_SEQ';
    IF v_count = 0 THEN
        EXECUTE IMMEDIATE 'CREATE SEQUENCE SAFEMASK_MASK_RULE_AUDIT_SEQ START WITH 1 INCREMENT BY 1 NOCACHE';
    END IF;

    SELECT COUNT(*) INTO v_count FROM all_tables
    WHERE owner = SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA')
		AND table_name = 'MASK_MASKING_RULE_AUDIT';
    IF v_count = 0 THEN
        EXECUTE IMMEDIATE '
            CREATE TABLE MASK_MASKING_RULE_AUDIT (
                ID                   NUMBER(19)         NOT NULL,
                MASKING_RULE_ID      NUMBER(19)         NOT NULL,
                ACTION               VARCHAR2(20 CHAR)  NOT NULL,
                RULE_NAME            VARCHAR2(100 CHAR) NOT NULL,
                MASKING_TYPE         VARCHAR2(30 CHAR)  NOT NULL,
                RULE_KIND            VARCHAR2(20 CHAR)  NOT NULL,
                PATTERN              VARCHAR2(1000 CHAR) NOT NULL,
                PRIORITY             NUMBER(10)         NOT NULL,
                ENABLED              NUMBER(1)          NOT NULL,
                DESCRIPTION          VARCHAR2(500 CHAR),
                RULE_VERSION         NUMBER(19)         NOT NULL,
                CHANGED_BY_MEMBER_ID NUMBER(19)         NOT NULL,
                CHANGED_BY_NAME      VARCHAR2(50 CHAR)  NOT NULL,
                CHANGE_REASON        VARCHAR2(300 CHAR) NOT NULL,
                CHANGED_AT           TIMESTAMP          NOT NULL,
                CONSTRAINT PK_MASK_RULE_AUDIT PRIMARY KEY (ID),
                CONSTRAINT CK_MASK_RULE_AUDIT_ACTION CHECK (ACTION IN
                    (''CREATED'', ''UPDATED'', ''ACTIVATED'', ''DEACTIVATED'')),
                CONSTRAINT CK_MASK_RULE_AUDIT_KIND CHECK (RULE_KIND IN (''KEYWORD'', ''REGEX'')),
                CONSTRAINT CK_MASK_RULE_AUDIT_ENABLED CHECK (ENABLED IN (0, 1))
            )';
	END IF;

	-- Hibernate update 등으로 테이블만 먼저 만들어진 부분 적용 상태도 보완합니다.
	SELECT COUNT(*) INTO v_count FROM all_indexes
	WHERE owner = SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA')
		AND index_name = 'IX_MASK_RULE_AUDIT_CHANGED';
	IF v_count = 0 THEN
		EXECUTE IMMEDIATE 'CREATE INDEX IX_MASK_RULE_AUDIT_CHANGED '
			|| 'ON MASK_MASKING_RULE_AUDIT (MASKING_RULE_ID, CHANGED_AT DESC)';
	END IF;
END;
/

COMMIT;
