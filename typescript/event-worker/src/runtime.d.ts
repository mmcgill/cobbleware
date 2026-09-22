import type { EventManagerEvent } from './events.ts';

interface EventManagerIterator extends AsyncIterable<EventManagerEvent> {}

declare global {
  var EventManager: {
    new (): EventManagerIterator;
  };
}

export {};
