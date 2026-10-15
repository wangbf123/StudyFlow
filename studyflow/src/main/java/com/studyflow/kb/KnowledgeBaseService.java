package com.studyflow.kb;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.studyflow.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class KnowledgeBaseService {

    private final KnowledgeBaseMapper knowledgeBaseMapper;

    @Transactional
    public KnowledgeBaseResponse create(Long userId, KnowledgeBaseCreateRequest request) {
        KnowledgeBaseEntity entity = new KnowledgeBaseEntity();
        entity.setUserId(userId);
        entity.setName(request.name());
        entity.setDescription(request.description());
        entity.setStatus("ACTIVE");
        knowledgeBaseMapper.insert(entity);
        return KnowledgeBaseResponse.from(entity);
    }

    public List<KnowledgeBaseResponse> list(Long userId) {
        return knowledgeBaseMapper.selectList(Wrappers.<KnowledgeBaseEntity>lambdaQuery()
                        .eq(KnowledgeBaseEntity::getUserId, userId)
                        .ne(KnowledgeBaseEntity::getStatus, "DELETED")
                        .orderByDesc(KnowledgeBaseEntity::getCreateTime))
                .stream()
                .map(KnowledgeBaseResponse::from)
                .toList();
    }

    public KnowledgeBaseResponse get(Long userId, Long id) {
        return KnowledgeBaseResponse.from(getOwnedActive(userId, id));
    }

    @Transactional
    public KnowledgeBaseResponse update(Long userId, Long id, KnowledgeBaseUpdateRequest request) {
        KnowledgeBaseEntity entity = getOwnedActive(userId, id);
        entity.setName(request.name());
        entity.setDescription(request.description());
        knowledgeBaseMapper.updateById(entity);
        return KnowledgeBaseResponse.from(entity);
    }

    @Transactional
    public void delete(Long userId, Long id) {
        KnowledgeBaseEntity entity = getOwnedActive(userId, id);
        entity.setStatus("DELETED");
        knowledgeBaseMapper.updateById(entity);
    }

    public KnowledgeBaseEntity getOwnedActive(Long userId, Long id) {
        KnowledgeBaseEntity entity = knowledgeBaseMapper.selectOne(Wrappers.<KnowledgeBaseEntity>lambdaQuery()
                .eq(KnowledgeBaseEntity::getId, id)
                .eq(KnowledgeBaseEntity::getUserId, userId)
                .eq(KnowledgeBaseEntity::getStatus, "ACTIVE"));
        if (entity == null) {
            throw new BusinessException("知识库不存在或无权限访问");
        }
        return entity;
    }
}
