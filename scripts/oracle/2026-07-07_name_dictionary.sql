-- ============================================================================
-- 이름 사전 테이블 생성 (2026-07-07, feat/8)
--
-- 배경:
--   이름 마스킹을 "성씨+글자수" 휴리스틱만으로 하면 "고마워" 같은 일반 단어가
--   이름으로 오탐됩니다. 이를 해결하기 위해 이름(성 제외) 사전을 DB에 두고,
--   "성씨 + 사전에 있는 이름" 조합일 때만 이름으로 판정합니다.
--
--   SOURCE 컬럼: 항목의 출처 구분 (SEED=시드 파일 자동 적재, CUSTOM=관리자 수동 등록).
--   시더는 SEED 출처만 시드 파일과 동기화(파일에서 빠진 이름은 삭제)하므로,
--   관리자가 추가한 이름(CUSTOM)은 재기동해도 유지됩니다.
--
--   기본 데이터는 애플리케이션 기동 시 시드 파일(korean-given-names.txt)에서
--   자동 적재/동기화되므로 이 스크립트는 테이블·시퀀스만 만들면 됩니다.
--
-- 실행 방법:
--   서비스 계정으로 전체 실행. (재실행 안전 — 이미 있으면 부족한 부분만 보완)
-- ============================================================================

SET SERVEROUTPUT ON

DECLARE
    v_count NUMBER;
BEGIN
    -- 시퀀스 생성 (없을 때만)
    SELECT COUNT(*) INTO v_count FROM user_sequences
    WHERE sequence_name = 'SAFEMASK_NAME_DICT_SEQ';
    IF v_count = 0 THEN
        EXECUTE IMMEDIATE 'CREATE SEQUENCE SAFEMASK_NAME_DICT_SEQ START WITH 1 INCREMENT BY 1 NOCACHE';
        DBMS_OUTPUT.PUT_LINE('SAFEMASK_NAME_DICT_SEQ 생성 완료');
    ELSE
        DBMS_OUTPUT.PUT_LINE('SAFEMASK_NAME_DICT_SEQ 이미 존재 — 건너뜀');
    END IF;

    -- 테이블 생성 (없을 때만)
    SELECT COUNT(*) INTO v_count FROM user_tables
    WHERE table_name = 'MASK_NAME_DICT';
    IF v_count = 0 THEN
        EXECUTE IMMEDIATE '
            CREATE TABLE MASK_NAME_DICT (
                ID         NUMBER(19)        NOT NULL,
                GIVEN_NAME VARCHAR2(10 CHAR) NOT NULL,
                SOURCE     VARCHAR2(10 CHAR),
                CREATED_AT TIMESTAMP         NOT NULL,
                CONSTRAINT PK_MASK_NAME_DICT PRIMARY KEY (ID),
                CONSTRAINT UK_MASK_NAME_DICT_GIVEN_NAME UNIQUE (GIVEN_NAME)
            )';
        DBMS_OUTPUT.PUT_LINE('MASK_NAME_DICT 생성 완료');
    ELSE
        DBMS_OUTPUT.PUT_LINE('MASK_NAME_DICT 이미 존재 — 컬럼 보완 확인');

        -- SOURCE 컬럼이 없는 기존 테이블이면 추가 (컬럼 도입 전 배포분 대응)
        SELECT COUNT(*) INTO v_count FROM user_tab_columns
        WHERE table_name = 'MASK_NAME_DICT' AND column_name = 'SOURCE';
        IF v_count = 0 THEN
            EXECUTE IMMEDIATE 'ALTER TABLE MASK_NAME_DICT ADD (SOURCE VARCHAR2(10 CHAR))';
            DBMS_OUTPUT.PUT_LINE('SOURCE 컬럼 추가 완료');
        ELSE
            DBMS_OUTPUT.PUT_LINE('SOURCE 컬럼 이미 존재 — 건너뜀');
        END IF;
    END IF;
END;
/

COMMIT;
