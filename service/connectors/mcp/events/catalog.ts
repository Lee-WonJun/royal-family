const string = { type: 'string' };
export const eventDefinition = {
  name: 'asset.record.updated',
  description: '시연 토지의 비교 가능한 후속 자료가 바뀌었습니다. 실제 소유권 변동 확정이 아닙니다. 상세 근거는 get_asset_changes로 조회하세요.',
  delivery: ['webhook'],
  inputSchema: { type: 'object', properties: { clan_id: { type: 'string', const: 'demo_a' }, asset_id: string,
    fields: { type: 'array', items: { type: 'string', enum: ['owner_name', 'owner_type', 'area_m2', 'land_category'] }, minItems: 1 } },
    required: ['clan_id', 'asset_id'], additionalProperties: false },
  payloadSchema: { type: 'object', properties: { clan_id: string, asset_id: string, change_id: string,
    from_version: { type: 'integer' }, to_version: { type: 'integer' }, changed_fields: { type: 'array', items: string }, source_kind: string,
    observed_at: string, summary: string, url: string, is_demo: { type: 'boolean' } },
    required: ['clan_id', 'asset_id', 'change_id', 'from_version', 'to_version', 'changed_fields', 'source_kind', 'observed_at', 'summary', 'url', 'is_demo'], additionalProperties: false },
};
