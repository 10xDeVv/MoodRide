'use client';

import React, { useState, useRef, useEffect } from 'react';

export type BottomSheetState = 'minimized' | 'halfway' | 'expanded';
export type BottomSheetTheme = 'planner' | 'results';

interface BottomSheetProps {
  state: BottomSheetState;
  onStateChange: (state: BottomSheetState) => void;
  theme?: BottomSheetTheme;
  children: React.ReactNode;
}

type TouchMode = 'sheet' | 'content' | null;

const SNAP_POINTS: Record<BottomSheetState, number> = {
  minimized: 10,
  halfway: 50,
  expanded: 100,
};

const SNAP_ORDER: BottomSheetState[] = ['minimized', 'halfway', 'expanded'];
const SNAP_ANIMATION = 'height 0.42s cubic-bezier(0.22, 1, 0.36, 1)';

export const BottomSheet: React.FC<BottomSheetProps> = ({
  state,
  onStateChange,
  theme = 'planner',
  children,
}) => {
  const containerRef = useRef<HTMLDivElement>(null);
  const contentRef = useRef<HTMLDivElement>(null);
  const [isDragging, setIsDragging] = useState(false);
  const [currentHeight, setCurrentHeight] = useState(0);
  const [isMobile, setIsMobile] = useState(false);

  const stateRef = useRef<BottomSheetState>(state);
  const heightRef = useRef(0);
  const touchModeRef = useRef<TouchMode>(null);
  const startYRef = useRef(0);
  const lastYRef = useRef(0);
  const startHeightRef = useRef(0);

  useEffect(() => {
    stateRef.current = state;
  }, [state]);

  useEffect(() => {
    heightRef.current = currentHeight;
  }, [currentHeight]);

  // Render for phone and iPad portrait widths that use the bottom-sheet layout.
  useEffect(() => {
    const checkMobile = () => {
      setIsMobile(window.innerWidth <= 1024);
    };
    checkMobile();
    window.addEventListener('resize', checkMobile);
    return () => window.removeEventListener('resize', checkMobile);
  }, []);

  const getHeightForState = (s: BottomSheetState): number => {
    if (!isMobile) return 0;
    const headerHeight = 64;
    const viewportHeight = window.innerHeight;
    const maxHeight = viewportHeight - headerHeight;
    return (SNAP_POINTS[s] / 100) * maxHeight;
  };

  const setSheetHeight = (height: number) => {
    const minHeight = getHeightForState('minimized');
    const maxHeight = getHeightForState('expanded');
    const constrainedHeight = Math.max(minHeight, Math.min(height, maxHeight));

    heightRef.current = constrainedHeight;
    setCurrentHeight(constrainedHeight);
    if (containerRef.current) {
      containerRef.current.style.height = `${constrainedHeight}px`;
    }
  };

  // Initialize height on mount, external state changes, and window resize.
  useEffect(() => {
    if (!isMobile) return;
    const updateHeight = () => {
      const height = getHeightForState(state);
      setSheetHeight(height);
      if (state !== 'expanded') {
        contentRef.current?.scrollTo({ top: 0 });
      }
    };
    updateHeight();
    window.addEventListener('resize', updateHeight);
    return () => window.removeEventListener('resize', updateHeight);
  }, [state, isMobile]); // eslint-disable-line react-hooks/exhaustive-deps

  const getSnapFromHeight = (height: number): BottomSheetState => {
    const distances = SNAP_ORDER.map((snap) => ({
      snap,
      distance: Math.abs(height - getHeightForState(snap)),
    }));
    return distances.sort((a, b) => a.distance - b.distance)[0].snap;
  };

  const animateToState = (nextState: BottomSheetState) => {
    onStateChange(nextState);
    setIsDragging(false);

    requestAnimationFrame(() => {
      setSheetHeight(getHeightForState(nextState));
      if (nextState !== 'expanded') {
        contentRef.current?.scrollTo({ top: 0, behavior: 'smooth' });
      }
    });
  };

  const finishSheetDrag = () => {
    const totalDeltaY = lastYRef.current - startYRef.current;
    const currentState = stateRef.current;
    const activeHeight = heightRef.current;
    const directionalThreshold = 36;
    const expandedHeight = getHeightForState('expanded');
    const halfwayHeight = getHeightForState('halfway');

    let nextState = getSnapFromHeight(activeHeight);

    if (totalDeltaY < -directionalThreshold) {
      nextState = activeHeight > halfwayHeight ? 'expanded' : 'halfway';
    } else if (totalDeltaY > directionalThreshold) {
      nextState = currentState === 'expanded' && activeHeight > halfwayHeight * 0.9
        ? 'halfway'
        : activeHeight < expandedHeight * 0.38
          ? 'minimized'
          : nextState;
    }

    animateToState(nextState);
  };

  const beginTouch = (clientY: number, mode: TouchMode) => {
    touchModeRef.current = mode;
    startYRef.current = clientY;
    lastYRef.current = clientY;
    startHeightRef.current = heightRef.current || getHeightForState(stateRef.current);
    if (mode === 'sheet') {
      setIsDragging(true);
    }
  };

  const handleTouchStart = (e: React.TouchEvent) => {
    if (!isMobile) return;

    const target = e.target as HTMLElement;
    const startedOnHandle = Boolean(target.closest('.bottom-sheet-drag-zone'));
    const clientY = e.touches[0].clientY;

    if (startedOnHandle || stateRef.current !== 'expanded') {
      beginTouch(clientY, 'sheet');
      return;
    }

    beginTouch(clientY, null);
  };

  const handleTouchMove = (e: React.TouchEvent) => {
    if (!isMobile || !containerRef.current) return;

    const clientY = e.touches[0].clientY;
    const startDeltaY = clientY - startYRef.current;
    const contentScrollTop = contentRef.current?.scrollTop ?? 0;

    if (touchModeRef.current === null) {
      const shouldCollapseSheet = startDeltaY > 4 && contentScrollTop <= 0;
      const shouldLetContentScroll = stateRef.current === 'expanded' && !shouldCollapseSheet;

      if (shouldCollapseSheet) {
        touchModeRef.current = 'sheet';
        startYRef.current = clientY;
        lastYRef.current = clientY;
        startHeightRef.current = heightRef.current || getHeightForState(stateRef.current);
        setIsDragging(true);
      } else if (shouldLetContentScroll) {
        touchModeRef.current = 'content';
      }
    }

    if (touchModeRef.current === 'sheet') {
      e.preventDefault();
      const nextHeight = startHeightRef.current - startDeltaY;
      setSheetHeight(nextHeight);
    }

    lastYRef.current = clientY;
  };

  const handleTouchEnd = () => {
    if (!isMobile) return;

    if (touchModeRef.current === 'sheet') {
      finishSheetDrag();
    } else {
      setIsDragging(false);
    }

    touchModeRef.current = null;
  };

  // Larger tablet and desktop layouts are handled by RoutePlanner's side panels.
  if (!isMobile) {
    return null;
  }

  return (
    <div
      ref={containerRef}
      className={`bottom-sheet-container bottom-sheet-${theme} bottom-sheet-${state}${isDragging ? ' is-dragging' : ''}`}
      style={{
        height: `${currentHeight}px`,
        transition: isDragging ? 'none' : SNAP_ANIMATION,
      }}
      onTouchStart={handleTouchStart}
      onTouchMove={handleTouchMove}
      onTouchEnd={handleTouchEnd}
      onTouchCancel={handleTouchEnd}
    >
      <div className="bottom-sheet-drag-zone" aria-hidden="true">
        <div className="bottom-sheet-handle" />
      </div>

      <div ref={contentRef} className="bottom-sheet-content">
        {children}
      </div>
    </div>
  );
};
