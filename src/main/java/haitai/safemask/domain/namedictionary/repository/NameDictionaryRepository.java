package haitai.safemask.domain.namedictionary.repository;

import haitai.safemask.domain.namedictionary.entity.NameDictionaryEntry;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NameDictionaryRepository extends JpaRepository<NameDictionaryEntry, Long> {
}
