import { renderHook } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { useAutoScroll } from '../useAutoScroll';

const createScrollContainer = () => ({
  scrollHeight: 1000,
  scrollTop: 0,
  clientHeight: 500,
  scrollTo: vi.fn(),
  addEventListener: vi.fn(),
  removeEventListener: vi.fn(),
});

describe('useAutoScroll', () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it('keeps a single smooth scroll and moves consecutive messages to the bottom immediately', () => {
    vi.useFakeTimers();
    const currentUserId = 'user-1';
    const { result, rerender } = renderHook(
      ({ messages }) => useAutoScroll(messages, currentUserId),
      { initialProps: { messages: [] } },
    );
    const container = createScrollContainer();
    result.current.containerRef.current = container;

    rerender({
      messages: [{ _id: 'message-1', sender: currentUserId }],
    });

    expect(container.scrollTo).toHaveBeenCalledTimes(1);
    expect(container.scrollTo).toHaveBeenLastCalledWith({
      top: container.scrollHeight,
      behavior: 'smooth',
    });

    rerender({
      messages: [
        { _id: 'message-1', sender: currentUserId },
        { _id: 'message-2', sender: currentUserId },
      ],
    });

    expect(container.scrollTo).toHaveBeenCalledTimes(2);
    expect(container.scrollTo).toHaveBeenLastCalledWith({
      top: container.scrollHeight,
      behavior: 'auto',
    });
    expect(vi.getTimerCount()).toBe(1);
  });

  it('uses smooth scrolling again after the previous scroll has completed', () => {
    vi.useFakeTimers();
    const currentUserId = 'user-1';
    const { result, rerender } = renderHook(
      ({ messages }) => useAutoScroll(messages, currentUserId),
      { initialProps: { messages: [] } },
    );
    const container = createScrollContainer();
    result.current.containerRef.current = container;

    rerender({ messages: [{ _id: 'message-1', sender: currentUserId }] });
    vi.advanceTimersByTime(300);
    rerender({
      messages: [
        { _id: 'message-1', sender: currentUserId },
        { _id: 'message-2', sender: currentUserId },
      ],
    });

    expect(container.scrollTo).toHaveBeenCalledTimes(2);
    expect(container.scrollTo).toHaveBeenLastCalledWith({
      top: container.scrollHeight,
      behavior: 'smooth',
    });
  });

  it('does not scroll a remote message after the user moves away from the bottom', () => {
    vi.useFakeTimers();
    const currentUserId = 'user-1';
    const remoteUserId = 'user-2';
    const { result, rerender } = renderHook(
      ({ messages }) => useAutoScroll(messages, currentUserId),
      { initialProps: { messages: [] } },
    );
    const container = createScrollContainer();
    result.current.containerRef.current = container;

    rerender({ messages: [{ _id: 'message-1', sender: remoteUserId }] });
    expect(container.scrollTo).toHaveBeenCalledTimes(1);

    container.scrollTop = 100;
    vi.advanceTimersByTime(300);

    expect(result.current.isNearBottom()).toBe(false);

    rerender({
      messages: [
        { _id: 'message-1', sender: remoteUserId },
        { _id: 'message-2', sender: remoteUserId },
      ],
    });

    expect(container.scrollTo).toHaveBeenCalledTimes(1);
  });
});
