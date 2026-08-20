import { Effects, type EffectT, type Program } from '@cobbleware/core';

const helloWorldEffect: EffectT = new Effects.SendChatT('Hello world');

export const handle: Program = function handle(state, _event) {
  return {
    nextState: state,
    effects: [helloWorldEffect],
  };
};
