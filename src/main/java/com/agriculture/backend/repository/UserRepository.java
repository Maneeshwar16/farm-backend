package com.agriculture.backend.repository;
import com.agriculture.backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
public interface UserRepository extends JpaRepository<User,Long>{
    boolean existsByEmail(String email);

    User findByEmail(String email);
}

