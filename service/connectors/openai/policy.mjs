import { aiPolicy } from '../../generated/domain/main.js';
import { AiProviderError } from './client.mjs';

// Serialization/error adaptation only; the policy belongs to the CLJS module.
export function policy(action, input = {}) {
  const result = aiPolicy(action, input);
  if (!result.ok) throw new AiProviderError(result.error.code, result.error.message,
    ['unsupported_feature', 'model_not_allowed'].includes(result.error.code) ? 400 : 502);
  return result.value;
}
const config = policy('config');
export const model = config.model;
export const routedModels = config.routed_models;
export const promptVersion = config.prompt_version;
export const promptVersionFor = feature => policy('prompt-version', { feature });
/** @type {string[]} */
export const aiFeatures = config.features;
export const mockResult = job => policy('mock-result', job);
