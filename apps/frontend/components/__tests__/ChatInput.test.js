import React from 'react';
import { render, waitFor, fireEvent } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import ChatInput from '../ChatInput';

describe('ChatInput', () => {
  it('renders the lazy emoji picker under React 19', async () => {
    const { container, getByLabelText } = render(
      <ChatInput
        fileInputRef={{ current: null }}
        room={{ participants: [] }}
      />
    );

    fireEvent.click(getByLabelText('이모티콘'));

    await waitFor(() => {
      expect(container.querySelector('em-emoji-picker')).toBeInTheDocument();
    });
  });

  it('does not submit when Enter confirms an IME composition', () => {
    const onSubmit = vi.fn();
    const { getByTestId } = render(
      <ChatInput
        onSubmit={onSubmit}
        fileInputRef={{ current: null }}
        room={{ participants: [] }}
      />
    );
    const input = getByTestId('chat-message-input');

    fireEvent.change(input, { target: { value: '중복 확인' } });
    fireEvent.compositionStart(input);
    fireEvent.keyDown(input, { key: 'Enter', code: 'Enter' });

    expect(onSubmit).not.toHaveBeenCalled();

    fireEvent.compositionEnd(input);
    fireEvent.keyDown(input, { key: 'Enter', code: 'Enter' });

    expect(onSubmit).toHaveBeenCalledTimes(1);
    expect(onSubmit).toHaveBeenCalledWith({
      type: 'text',
      content: '중복 확인',
    });
  });

  it('does not submit IME Enter events reported with keyCode 229', () => {
    const onSubmit = vi.fn();
    const { getByTestId } = render(
      <ChatInput
        onSubmit={onSubmit}
        fileInputRef={{ current: null }}
        room={{ participants: [] }}
      />
    );
    const input = getByTestId('chat-message-input');

    fireEvent.change(input, { target: { value: '한글' } });
    fireEvent.keyDown(input, { key: 'Enter', keyCode: 229 });

    expect(onSubmit).not.toHaveBeenCalled();
  });
});
